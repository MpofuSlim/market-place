package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.ListingImageRepository.ImageMeta;
import com.innbucks.marketplaceservice.catalog.dto.DeliveryTownFee;
import com.innbucks.marketplaceservice.catalog.dto.ListingCreateRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingOptionResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingStatusRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingUpdateRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingVariantResponse;
import com.innbucks.marketplaceservice.catalog.dto.VariantRequest;
import com.innbucks.marketplaceservice.catalog.variant.ListingVariant;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.support.TestTowns;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link ListingService}: merchant scope comes
 * from the JWT (missing/malformed = 403 {@code merchant_scope_missing}),
 * ownership is enforced on every row touch (403 {@code listing_not_owned}),
 * the per-merchant row cap holds, all free text passes the jsoup sanitizer
 * before storage, the stored currency is ALWAYS the cell currency, the
 * category code is validated against the taxonomy table, and the V3 gallery
 * invariant (one primary whenever any images exist; publish gate on ACTIVE)
 * is preserved by every image mutation. V19: the options editor (create,
 * keep / convert / replace on update, the per-cell switch, the floor rule on a
 * price change, the one-option quick restock) runs through the REAL
 * {@link ListingStock} and option planner over a small fake of the native
 * stock statements, so the row lock, the recompute and the restock event are
 * exercised as they run on Postgres.
 */
class ListingServiceTest {

    private static final int MAX_PER_MERCHANT = 5;
    private static final UUID MERCHANT_ID =
            UUID.fromString("7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54");
    private static final UUID SHOP_ID =
            UUID.fromString("1b7d3f5a-9c2e-4a6b-8d1f-3e5c7a9b2d4f");
    private static final AuthenticatedUser MERCHANT = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("MERCHANT_ADMIN"),
            MERCHANT_ID.toString(), SHOP_ID.toString(), null, "ZW");

    private ListingRepository listingRepository;
    private com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository variantRepository;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private com.innbucks.marketplaceservice.seller.SellerService sellerService;
    private ListingImageRepository listingImageRepository;
    private CategoryRepository categoryRepository;
    private ListingDeliveryTownRepository deliveryTownRepository;
    private AuditService auditService;
    private SimpleMeterRegistry registry;
    private ListingService service;

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        listingImageRepository = mock(ListingImageRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        deliveryTownRepository = mock(ListingDeliveryTownRepository.class);
        auditService = mock(AuditService.class);
        registry = new SimpleMeterRegistry();
        // Taxonomy accepts everything unless a test narrows it — the
        // unknown-category test re-stubs the specific code to false.
        when(categoryRepository.existsById(anyString())).thenReturn(true);
        sellerService = mock(com.innbucks.marketplaceservice.seller.SellerService.class);
        // A merchant with no trust record may publish (SellerService.canPublish
        // returns true for an absent row), which is the pre-V8 behaviour these
        // existing cases were written against.
        when(sellerService.canPublish(any())).thenReturn(true);
        variantRepository = mock(com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository.class);
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        stockFake();
        service = newService(true);
    }

    /**
     * The service as a cell wires it, with the REAL stock mover and option
     * planner over the mocked repositories, and the per-cell
     * {@code marketplace.listing.variants-enabled} switch as given.
     */
    private ListingService newService(boolean variantsEnabled) {
        ListingStock listingStock = new ListingStock(listingRepository, variantRepository,
                eventPublisher, new MarketplaceMetrics(registry));
        return new ListingService(listingRepository, listingImageRepository,
                sellerService,
                categoryRepository,
                new ListingViewAssembler(listingImageRepository, categoryRepository, sellerService,
                        deliveryTownRepository, TestTowns.zimbabwe(),
                        mock(com.innbucks.marketplaceservice.pickup.CollectionPointViews.class),
                        variantRepository),
                auditService, new MarketplaceMetrics(registry),
                deliveryTownRepository, TestTowns.zimbabwe(),
                listingStock,
                new com.innbucks.marketplaceservice.catalog.variant.ListingVariantService(
                        variantRepository, listingStock, variantsEnabled, 50),
                "USD", MAX_PER_MERCHANT);
    }

    /** Current stock per listing, as the native statements see it. */
    private final java.util.Map<UUID, Integer> stock = new java.util.HashMap<>();

    /** V19 {@code listing_variant}: the option rows (the managed entities, by
     *  id) and, separately, what their {@code stock_qty} column holds — the
     *  column is not updatable through the entity, so the two can differ. */
    private final java.util.Map<UUID, ListingVariant> optionRows = new java.util.LinkedHashMap<>();
    private final java.util.Map<UUID, Integer> optionStock = new java.util.HashMap<>();

    /**
     * A small stand-in for the native stock statements: the row lock reads the
     * stock this map holds (10, the {@link #owned} fixture's, when unset), the
     * plain set writes it, and the after-read returns it. Enough for the
     * update path's lock -> set -> settle to behave as it does on Postgres.
     *
     * <p>V19: the option statements too. An option's set writes only its
     * column, an INSERT (saveAll of a new row) carries the entity's stock, a
     * delete removes the row, and the recompute sets the listing's total to
     * the sum of its option rows (0 rows updated when the listing has none —
     * the {@code has_variants = TRUE} guard).
     */
    private void stockFake() {
        when(listingRepository.lockForStock(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return stockRow(stock.getOrDefault(id, 10), !optionsOf(id).isEmpty());
        });
        when(listingRepository.setPlainStock(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> {
                    stock.put(inv.getArgument(0), inv.getArgument(1));
                    return 1;
                });
        when(listingRepository.stockQtyOf(any())).thenAnswer(inv ->
                stock.getOrDefault(inv.<UUID>getArgument(0), 10));
        // The lock-free seller read the editor checks ownership with, BEFORE
        // the lock: the seller of whatever findById is stubbed to return.
        when(listingRepository.merchantIdOf(any())).thenAnswer(inv ->
                listingRepository.findById(inv.<UUID>getArgument(0))
                        .map(Listing::getMerchantId).orElse(null));
        when(listingRepository.recomputeStockTotal(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            List<ListingVariant> rows = optionsOf(id);
            if (rows.isEmpty()) {
                return 0;
            }
            stock.put(id, rows.stream().mapToInt(row -> optionStock.get(row.getId())).sum());
            return 1;
        });
        when(variantRepository.setStock(any(), any(), anyInt(), any()))
                .thenAnswer(inv -> {
                    ListingVariant row = optionRows.get(inv.<UUID>getArgument(0));
                    if (row == null || !row.getListingId().equals(inv.getArgument(1))) {
                        return 0;
                    }
                    optionStock.put(row.getId(), inv.getArgument(2));
                    return 1;
                });
        when(variantRepository.saveAll(any())).thenAnswer(inv -> {
            List<ListingVariant> saved = new java.util.ArrayList<>();
            for (ListingVariant row : inv.<Iterable<ListingVariant>>getArgument(0)) {
                optionRows.put(row.getId(), row);
                optionStock.putIfAbsent(row.getId(), row.getStockQty());
                saved.add(row);
            }
            return saved;
        });
        org.mockito.Mockito.doAnswer(inv -> {
            for (ListingVariant row : inv.<Iterable<ListingVariant>>getArgument(0)) {
                optionRows.remove(row.getId());
                optionStock.remove(row.getId());
            }
            return null;
        }).when(variantRepository).deleteAll(any());
        when(variantRepository.findByListingIdOrderByPositionAsc(any()))
                .thenAnswer(inv -> optionsOf(inv.getArgument(0)));
    }

    private List<ListingVariant> optionsOf(UUID listingId) {
        return optionRows.values().stream()
                .filter(row -> row.getListingId().equals(listingId))
                .sorted(java.util.Comparator.comparingInt(ListingVariant::getPosition))
                .toList();
    }

    static StockRow stockRow(int qty, boolean hasVariants) {
        return new StockRow() {
            @Override
            public String getStatus() {
                return "ACTIVE";
            }

            @Override
            public Boolean getHasVariants() {
                return hasVariants;
            }

            @Override
            public Integer getStockQty() {
                return qty;
            }
        };
    }

    private static ListingCreateRequest createReq(String title, String description,
                                                  String categoryCode) {
        return new ListingCreateRequest(title, description, categoryCode,
                null, null, null, 2599L, 120, null);
    }

    private static ListingUpdateRequest updateReq(String title, String description,
                                                  String categoryCode, long priceCents, int stockQty) {
        return new ListingUpdateRequest(title, description, categoryCode,
                null, null, null, priceCents, stockQty);
    }

    private static Listing owned(UUID listingId) {
        Instant now = Instant.now();
        return Listing.builder()
                .id(listingId).merchantId(MERCHANT_ID).title("Old title")
                .description("Old description").categoryCode("other")
                .priceCents(1000).currency("USD").stockQty(10)
                .status(ListingStatus.DRAFT).createdAt(now).updatedAt(now)
                .build();
    }

    private static AuthenticatedUser callerWithMerchantClaim(String merchantClaim) {
        return new AuthenticatedUser(UUID.randomUUID().toString(),
                Set.of("MERCHANT_ADMIN"), merchantClaim, null, null, "ZW");
    }

    /** ImageMeta test double (the bytes-free projection interface). */
    private static ImageMeta meta(UUID id, UUID listingId, boolean primary, int position) {
        return new ImageMeta() {
            @Override public UUID getId() { return id; }
            @Override public UUID getListingId() { return listingId; }
            @Override public String getContentType() { return "image/png"; }
            @Override public boolean isPrimaryImage() { return primary; }
            @Override public int getPosition() { return position; }
        };
    }

    // ------------------------------------------------------------------
    // Create: scope, sanitization, currency, taxonomy, condition, location
    // ------------------------------------------------------------------

    @Test
    void createSanitizesFreeTextAppliesJwtScopeAndCellCurrency() {
        service.create(MERCHANT, new ListingCreateRequest(
                "  <b>Solar Lantern</b> 20W  ",
                "Portable <i>bright</i> lantern",
                "home-garden", ItemCondition.USED_GOOD,
                "  <b>Harare</b> ", "Avondale <script>x</script>",
                2599L, 120, null));

        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(saved.capture());
        Listing listing = saved.getValue();
        // HTML stripped, entities plain, whitespace trimmed.
        assertEquals("Solar Lantern 20W", listing.getTitle());
        assertEquals("Portable bright lantern", listing.getDescription());
        assertEquals("home-garden", listing.getCategoryCode());
        assertEquals(ItemCondition.USED_GOOD, listing.getCondition());
        // Location free text passes the same sanitizer (script bodies are
        // data nodes and vanish entirely).
        assertEquals("Harare", listing.getCity());
        assertEquals("Avondale", listing.getArea());
        // Scope from the JWT claims, never a request body.
        assertEquals(MERCHANT_ID, listing.getMerchantId());
        assertEquals(SHOP_ID, listing.getShopId());
        // Currency is ALWAYS the cell currency — the request has no such field.
        assertEquals("USD", listing.getCurrency());
        assertEquals(ListingStatus.DRAFT, listing.getStatus());
        assertEquals(2599L, listing.getPriceCents());
        assertEquals(120, listing.getStockQty());

        verify(auditService).record(eq(AuditEventType.LISTING_CREATED),
                eq(MERCHANT.uuid()), eq(listing.getId().toString()), anyMap());
        assertEquals(1.0, registry.get("marketplace.listings.created").counter().count());
        // The merchant becomes a seller by listing: the record is ensured once
        // every check has passed, before the listing row is written.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(sellerService, listingRepository);
        order.verify(sellerService).ensureExists(MERCHANT_ID);
        order.verify(listingRepository).save(any());
    }

    @Test
    void createDefaultsCategoryToOtherAndConditionToNew() {
        service.create(MERCHANT, createReq("Solar Lantern", null, null));

        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(saved.capture());
        assertEquals("other", saved.getValue().getCategoryCode());
        assertEquals(ItemCondition.NEW, saved.getValue().getCondition());
        assertNull(saved.getValue().getCity());
        assertNull(saved.getValue().getArea());
    }

    @Test
    void createNormalisesTheCategoryCodeBeforeValidation() {
        service.create(MERCHANT, createReq("Solar Lantern", null, "  Home-Garden  "));

        verify(categoryRepository).existsById("home-garden");
        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(saved.capture());
        assertEquals("home-garden", saved.getValue().getCategoryCode());
    }

    @Test
    void createWithUnknownCategoryCodeIs400UnknownCategory() {
        when(categoryRepository.existsById("not-a-category")).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(MERCHANT, createReq("Solar Lantern", null, "not-a-category")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("unknown_category", ex.code());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void createRejectsATitleThatSanitizesToNothing() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(MERCHANT, createReq("<img src=x>", "desc", null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("title_invalid", ex.code());
        verify(listingRepository, never()).save(any());
        verify(sellerService, never()).ensureExists(any());
    }

    @Test
    void createStoresNullWhenDescriptionSanitizesToBlank() {
        service.create(MERCHANT, createReq("Solar Lantern", "<b> </b>", null));

        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(saved.capture());
        assertNull(saved.getValue().getDescription());
    }

    @Test
    void malformedShopIdClaimMapsToNullNotAFailure() {
        AuthenticatedUser noUsableShop = new AuthenticatedUser(
                UUID.randomUUID().toString(), Set.of("MERCHANT_ADMIN"),
                MERCHANT_ID.toString(), "not-a-uuid", null, "ZW");

        service.create(noUsableShop, createReq("Solar Lantern", null, null));

        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(saved.capture());
        assertNull(saved.getValue().getShopId());
    }

    // ------------------------------------------------------------------
    // Merchant scope
    // ------------------------------------------------------------------

    @Test
    void missingMerchantClaimIs403MerchantScopeMissing() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(callerWithMerchantClaim(null),
                        createReq("Solar Lantern", null, null)));

        assertEquals(HttpStatus.FORBIDDEN, ex.status());
        assertEquals("merchant_scope_missing", ex.code());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void malformedMerchantClaimIs403MerchantScopeMissing() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.update(callerWithMerchantClaim("not-a-uuid"),
                        UUID.randomUUID(),
                        updateReq("Title", null, null, 1000L, 5)));

        assertEquals(HttpStatus.FORBIDDEN, ex.status());
        assertEquals("merchant_scope_missing", ex.code());
        verify(listingRepository, never()).findById(any());
    }

    // ------------------------------------------------------------------
    // Create with an inline gallery (the multipart one-shot variant)
    // ------------------------------------------------------------------

    @Test
    void createWithInlineImageStoresThePrimaryRowInTheSameTransaction() {
        service.create(MERCHANT, createReq("Solar Lantern", "desc", "home-garden"),
                new MockMultipartFile("image", "photo.png", "image/png", pngBytes()), null);

        verify(listingRepository).save(any(Listing.class));
        ArgumentCaptor<ListingImage> savedImage = ArgumentCaptor.forClass(ListingImage.class);
        verify(listingImageRepository).save(savedImage.capture());
        assertArrayEquals(pngBytes(), savedImage.getValue().getImageBytes());
        assertEquals("image/png", savedImage.getValue().getContentType());
        assertTrue(savedImage.getValue().isPrimaryImage());
        assertEquals(0, savedImage.getValue().getPosition());
    }

    @Test
    void createWithPrimaryAndAdditionalImagesMarksOnlyTheFirstPrimary() {
        service.create(MERCHANT, createReq("Solar Lantern", "desc", null),
                new MockMultipartFile("image", "main.png", "image/png", pngBytes()),
                List.of(new MockMultipartFile("images", "a.png", "image/png", pngBytes()),
                        new MockMultipartFile("images", "b.png", "image/png", pngBytes())));

        ArgumentCaptor<ListingImage> savedImages = ArgumentCaptor.forClass(ListingImage.class);
        verify(listingImageRepository, times(3)).save(savedImages.capture());
        List<ListingImage> images = savedImages.getAllValues();
        assertTrue(images.get(0).isPrimaryImage());
        assertFalse(images.get(1).isPrimaryImage());
        assertFalse(images.get(2).isPrimaryImage());
        assertEquals(List.of(0, 1, 2),
                images.stream().map(ListingImage::getPosition).toList());
    }

    @Test
    void createWithoutAnExplicitPrimaryPromotesTheFirstAdditionalImage() {
        // The gallery invariant: whenever images exist, one is primary — a
        // create that produced a primary-less gallery could never publish.
        service.create(MERCHANT, createReq("Solar Lantern", "desc", null), null,
                List.of(new MockMultipartFile("images", "a.png", "image/png", pngBytes()),
                        new MockMultipartFile("images", "b.png", "image/png", pngBytes())));

        ArgumentCaptor<ListingImage> savedImages = ArgumentCaptor.forClass(ListingImage.class);
        verify(listingImageRepository, times(2)).save(savedImages.capture());
        assertTrue(savedImages.getAllValues().get(0).isPrimaryImage());
        assertFalse(savedImages.getAllValues().get(1).isPrimaryImage());
    }

    @Test
    void createWithMoreThanNineAdditionalImagesIs400TooManyImages() {
        List<MultipartFile> tenExtras = java.util.stream.IntStream.range(0, 10)
                .<MultipartFile>mapToObj(i -> new MockMultipartFile(
                        "images", "x" + i + ".png", "image/png", pngBytes()))
                .toList();

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(MERCHANT, createReq("Solar Lantern", null, null),
                        new MockMultipartFile("image", "main.png", "image/png", pngBytes()),
                        tenExtras));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("too_many_images", ex.code());
        verify(listingRepository, never()).save(any());
        verify(listingImageRepository, never()).save(any());
    }

    @Test
    void createWithAnInvalidAdditionalImageRefusesTheWholeCreate() {
        // Atomicity: a bad file ANYWHERE in the gallery must never leave a
        // half-created listing behind — validation runs BEFORE the insert.
        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(MERCHANT, createReq("Solar Lantern", "desc", null),
                        new MockMultipartFile("image", "main.png", "image/png", pngBytes()),
                        List.of(new MockMultipartFile("images", "fake.png", "image/png",
                                "not-an-image".getBytes()))));

        assertEquals("unsupported_image_type", ex.code());
        verify(listingRepository, never()).save(any());
        verify(listingImageRepository, never()).save(any());
    }

    @Test
    void createWithAnExplicitlyEmptyImagePartIsImageRequired() {
        // Only an ABSENT part means "no image" — a sent-but-empty part is a
        // client bug and refuses the create.
        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(MERCHANT, createReq("Solar Lantern", "desc", null),
                        new MockMultipartFile("image", "empty.png", "image/png", new byte[0]), null));

        assertEquals("image_required", ex.code());
        verify(listingRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Per-merchant cap
    // ------------------------------------------------------------------

    @Test
    void createAtThePerMerchantCapConflicts() {
        when(listingRepository.countByMerchantId(MERCHANT_ID))
                .thenReturn((long) MAX_PER_MERCHANT);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(MERCHANT, createReq("Solar Lantern", null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("listing_limit_reached", ex.code());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void createJustUnderTheCapSucceeds() {
        when(listingRepository.countByMerchantId(MERCHANT_ID))
                .thenReturn((long) MAX_PER_MERCHANT - 1);

        service.create(MERCHANT, createReq("Solar Lantern", null, null));

        verify(listingRepository).save(any(Listing.class));
    }

    // ------------------------------------------------------------------
    // Ownership
    // ------------------------------------------------------------------

    @Test
    void updateOfAnotherMerchantsListingIs403ListingNotOwned() {
        UUID listingId = UUID.randomUUID();
        Listing foreign = owned(listingId);
        foreign.setMerchantId(UUID.randomUUID());
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(foreign));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.update(MERCHANT, listingId,
                        updateReq("New title", null, null, 1000L, 5)));

        assertEquals(HttpStatus.FORBIDDEN, ex.status());
        assertEquals("listing_not_owned", ex.code());
        verify(listingRepository, never()).save(any());
        // Refused BEFORE the row lock: a merchant must not be able to stall a
        // competitor's orders by editing their listing id.
        verify(listingRepository, never()).lockForStock(any());
    }

    @Test
    void updateOfAMissingListingIs404() {
        UUID listingId = UUID.randomUUID();
        when(listingRepository.findById(listingId)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.update(MERCHANT, listingId,
                        updateReq("New title", null, null, 1000L, 5)));

        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("listing_not_found", ex.code());
    }

    @Test
    void statusChangeOfAnotherMerchantsListingIs403ListingNotOwned() {
        UUID listingId = UUID.randomUUID();
        Listing foreign = owned(listingId);
        foreign.setMerchantId(UUID.randomUUID());
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(foreign));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.changeStatus(MERCHANT, listingId,
                        new ListingStatusRequest(ListingStatus.ACTIVE)));

        assertEquals(HttpStatus.FORBIDDEN, ex.status());
        assertEquals("listing_not_owned", ex.code());
        assertEquals(ListingStatus.DRAFT, foreign.getStatus()); // untouched
    }

    // ------------------------------------------------------------------
    // Update: sanitization + server-owned fields
    // ------------------------------------------------------------------

    @Test
    void updateSanitizesFreeTextAndNeverTouchesCurrencyOrScope() {
        UUID listingId = UUID.randomUUID();
        Listing listing = owned(listingId);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

        service.update(MERCHANT, listingId, new ListingUpdateRequest(
                "Nice <img src=x onerror=alert(1)>lamp",
                "Now <b>brighter</b>", "garden-outdoor", ItemCondition.USED_FAIR,
                "Bulawayo", null, 2399L, 150));

        assertEquals("Nice lamp", listing.getTitle());
        assertEquals("Now brighter", listing.getDescription());
        assertEquals("garden-outdoor", listing.getCategoryCode());
        assertEquals(ItemCondition.USED_FAIR, listing.getCondition());
        assertEquals("Bulawayo", listing.getCity());
        assertNull(listing.getArea());
        assertEquals(2399L, listing.getPriceCents());
        assertEquals(150, listing.getStockQty());
        // Server-owned fields survive a full-replace update untouched.
        assertEquals("USD", listing.getCurrency());
        assertEquals(MERCHANT_ID, listing.getMerchantId());
        assertEquals(ListingStatus.DRAFT, listing.getStatus());
        verify(listingRepository).save(listing);
        verify(auditService).record(eq(AuditEventType.LISTING_UPDATED),
                eq(MERCHANT.uuid()), eq(listingId.toString()), anyMap());
    }

    @Test
    void updateWithUnknownCategoryCodeIs400AndTouchesNothing() {
        UUID listingId = UUID.randomUUID();
        Listing listing = owned(listingId);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(categoryRepository.existsById("bogus")).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.update(MERCHANT, listingId,
                        updateReq("New title", null, "bogus", 1000L, 5)));

        assertEquals("unknown_category", ex.code());
        assertEquals("other", listing.getCategoryCode());
        verify(listingRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Status change + the publish gate
    // ------------------------------------------------------------------

    @Test
    void changeStatusAppliesAndAuditsFromAndTo() {
        UUID listingId = UUID.randomUUID();
        Listing listing = owned(listingId);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingImageRepository.existsByListingIdAndPrimaryImageTrue(listingId)).thenReturn(true);

        service.changeStatus(MERCHANT, listingId,
                new ListingStatusRequest(ListingStatus.ACTIVE));

        assertEquals(ListingStatus.ACTIVE, listing.getStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(AuditEventType.LISTING_STATUS_CHANGED),
                eq(MERCHANT.uuid()), eq(listingId.toString()), meta.capture());
        assertEquals("DRAFT", meta.getValue().get("from"));
        assertEquals("ACTIVE", meta.getValue().get("to"));
    }

    @Test
    void publishGate_activatingWithoutAPrimaryImageIs422() {
        UUID listingId = UUID.randomUUID();
        Listing listing = owned(listingId);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingImageRepository.existsByListingIdAndPrimaryImageTrue(listingId)).thenReturn(false);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.changeStatus(MERCHANT, listingId,
                        new ListingStatusRequest(ListingStatus.ACTIVE)));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.status());
        assertEquals("primary_image_required", ex.code());
        assertEquals(ListingStatus.DRAFT, listing.getStatus()); // untouched
        verify(listingRepository, never()).save(any());
    }

    @Test
    void publishGate_onlyGuardsTheTransitionToActive() {
        // Deactivating (and any non-ACTIVE target) needs no image; an
        // already-ACTIVE listing re-sent ACTIVE is not a transition and is
        // untouched by the gate — pre-V3 ACTIVE rows keep working.
        UUID listingId = UUID.randomUUID();
        Listing active = owned(listingId);
        active.setStatus(ListingStatus.ACTIVE);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(active));

        service.changeStatus(MERCHANT, listingId, new ListingStatusRequest(ListingStatus.ACTIVE));
        service.changeStatus(MERCHANT, listingId, new ListingStatusRequest(ListingStatus.INACTIVE));

        assertEquals(ListingStatus.INACTIVE, active.getStatus());
        verify(listingImageRepository, never()).existsByListingIdAndPrimaryImageTrue(any());
    }

    // ------------------------------------------------------------------
    // Server-side range re-checks (defense against non-HTTP callers)
    // ------------------------------------------------------------------

    @Test
    void priceOutOfRangeIsRejected() {
        ApiException zero = assertThrows(ApiException.class, () -> service.create(MERCHANT,
                new ListingCreateRequest("Solar Lantern", null, null, null, null, null, 0L, 10, null)));
        ApiException over = assertThrows(ApiException.class, () -> service.create(MERCHANT,
                new ListingCreateRequest("Solar Lantern", null, null, null, null, null,
                        100_000_001L, 10, null)));

        assertEquals(HttpStatus.BAD_REQUEST, zero.status());
        assertEquals("price_out_of_range", zero.code());
        assertEquals("price_out_of_range", over.code());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void stockOutOfRangeIsRejected() {
        ApiException negative = assertThrows(ApiException.class, () -> service.create(MERCHANT,
                new ListingCreateRequest("Solar Lantern", null, null, null, null, null, 1000L, -1, null)));
        ApiException over = assertThrows(ApiException.class, () -> service.create(MERCHANT,
                new ListingCreateRequest("Solar Lantern", null, null, null, null, null,
                        1000L, 1_000_001, null)));

        assertEquals(HttpStatus.BAD_REQUEST, negative.status());
        assertEquals("stock_out_of_range", negative.code());
        assertEquals("stock_out_of_range", over.code());
        verify(listingRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Pagination hygiene
    // ------------------------------------------------------------------

    @Test
    void listMineClampsPageAndSizeAndSortsNewestFirst() {
        when(listingRepository.findByMerchantId(eq(MERCHANT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.listMine(MERCHANT, -5, 500, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(listingRepository).findByMerchantId(eq(MERCHANT_ID), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(CatalogService.MAX_PAGE_SIZE, pageable.getValue().getPageSize());
        assertEquals(Sort.by(Sort.Direction.DESC, "createdAt"), pageable.getValue().getSort());
    }

    @Test
    void merchantListMineIgnoresTheMerchantFilter() {
        // A merchant can only ever see their own — the SUPER_ADMIN-only filter
        // must not let them pivot to another merchant's listings.
        when(listingRepository.findByMerchantId(eq(MERCHANT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.listMine(MERCHANT, 0, 20, UUID.randomUUID());

        verify(listingRepository).findByMerchantId(eq(MERCHANT_ID), any(Pageable.class));
        verify(listingRepository, never()).findAll(any(Pageable.class));
    }

    // ------------------------------------------------------------------
    // SUPER_ADMIN: fleet oversight (ownership bypass, on-behalf create,
    // all-listings reads)
    // ------------------------------------------------------------------

    private static final AuthenticatedUser SUPER_ADMIN = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("SUPER_ADMIN"), null, null, null, "ZW");

    @Test
    void superAdminCreateWithoutMerchantIdIs400MerchantIdRequired() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(SUPER_ADMIN, createReq("Solar Lantern", null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("merchant_id_required", ex.code());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void superAdminCreatesOnBehalfOfTheNamedMerchant() {
        service.create(SUPER_ADMIN, new ListingCreateRequest(
                "Solar Lantern", null, null, null, null, null, 2599L, 120, MERCHANT_ID));

        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(saved.capture());
        // The one deliberate exception to merchant-scope-from-JWT: the admin
        // token has no merchant claim, so the request names the target.
        assertEquals(MERCHANT_ID, saved.getValue().getMerchantId());
        verify(auditService).record(eq(AuditEventType.LISTING_CREATED),
                eq(SUPER_ADMIN.uuid()), eq(saved.getValue().getId().toString()), anyMap());
    }

    @Test
    void merchantAdminSendingAForeignMerchantIdIs422ScopeMismatch() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(MERCHANT, new ListingCreateRequest(
                        "Solar Lantern", null, null, null, null, null,
                        2599L, 120, UUID.randomUUID())));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.status());
        assertEquals("merchant_scope_mismatch", ex.code());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void merchantAdminSendingTheirOwnMerchantIdIsAccepted() {
        service.create(MERCHANT, new ListingCreateRequest(
                "Solar Lantern", null, null, null, null, null, 2599L, 120, MERCHANT_ID));

        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(saved.capture());
        assertEquals(MERCHANT_ID, saved.getValue().getMerchantId());
    }

    @Test
    void superAdminUpdatesAnotherMerchantsListingWithoutAMerchantClaim() {
        UUID listingId = UUID.randomUUID();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(owned(listingId)));

        service.update(SUPER_ADMIN, listingId,
                updateReq("New title", null, null, 1000L, 5));

        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(saved.capture());
        assertEquals("New title", saved.getValue().getTitle());
        // The row keeps ITS merchant — an admin edit never re-parents a listing.
        assertEquals(MERCHANT_ID, saved.getValue().getMerchantId());
    }

    @Test
    void superAdminChangesStatusOfAnotherMerchantsListing() {
        UUID listingId = UUID.randomUUID();
        Listing listing = owned(listingId);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        when(listingImageRepository.existsByListingIdAndPrimaryImageTrue(listingId)).thenReturn(true);

        service.changeStatus(SUPER_ADMIN, listingId,
                new ListingStatusRequest(ListingStatus.ACTIVE));

        assertEquals(ListingStatus.ACTIVE, listing.getStatus());
    }

    @Test
    void superAdminListMineReturnsAllListings() {
        when(listingRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.listMine(SUPER_ADMIN, 0, 20, null);

        verify(listingRepository).findAll(any(Pageable.class));
        verify(listingRepository, never()).findByMerchantId(any(), any(Pageable.class));
    }

    @Test
    void superAdminListMineWithFilterNarrowsToThatMerchant() {
        when(listingRepository.findByMerchantId(eq(MERCHANT_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.listMine(SUPER_ADMIN, 0, 20, MERCHANT_ID);

        verify(listingRepository).findByMerchantId(eq(MERCHANT_ID), any(Pageable.class));
        verify(listingRepository, never()).findAll(any(Pageable.class));
    }

    // ------------------------------------------------------------------
    // Gallery: primary upload/replace, add, delete, promote, swap —
    // validation keeps event-service's banner discipline
    // ------------------------------------------------------------------

    /** Real PNG magic bytes (89 50 4E 47 0D 0A 1A 0A) + filler. */
    private static byte[] pngBytes() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
    }

    /** GIF89a magic bytes — the format we deliberately refuse. */
    private static byte[] gifBytes() {
        return new byte[]{0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 2, 3, 4, 5, 6};
    }

    private Listing stubOwned(UUID listingId) {
        Listing listing = owned(listingId);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));
        return listing;
    }

    @Test
    void uploadCreatesThePrimaryWhenTheGalleryIsEmpty() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        when(listingImageRepository.findByListingIdAndPrimaryImageTrue(listingId))
                .thenReturn(Optional.empty());
        when(listingImageRepository.maxPosition(listingId)).thenReturn(-1);

        var response = service.uploadImage(MERCHANT, listingId,
                new MockMultipartFile("image", "photo.png", "image/png", pngBytes()));

        ArgumentCaptor<ListingImage> saved = ArgumentCaptor.forClass(ListingImage.class);
        verify(listingImageRepository).save(saved.capture());
        assertArrayEquals(pngBytes(), saved.getValue().getImageBytes());
        assertEquals("image/png", saved.getValue().getContentType());
        assertTrue(saved.getValue().isPrimaryImage());
        assertEquals(0, saved.getValue().getPosition());
        verify(auditService).record(eq(AuditEventType.LISTING_IMAGE_UPDATED),
                eq(MERCHANT.uuid()), eq(listingId.toString()), anyMap());
        assertEquals(response.id(), listingId);
    }

    @Test
    void uploadReplacesTheExistingPrimaryInPlaceKeepingItsId() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        UUID imageId = UUID.randomUUID();
        ListingImage existing = ListingImage.builder()
                .id(imageId).listingId(listingId)
                .imageBytes(new byte[]{9, 9, 9}).contentType("image/jpeg")
                .primaryImage(true).position(0).createdAt(Instant.now())
                .isNew(false)
                .build();
        when(listingImageRepository.findByListingIdAndPrimaryImageTrue(listingId))
                .thenReturn(Optional.of(existing));

        service.uploadImage(MERCHANT, listingId,
                new MockMultipartFile("image", "photo.png", "image/png", pngBytes()));

        // Same row, new bytes/content type — cached per-image URLs stay valid.
        assertEquals(imageId, existing.getId());
        assertArrayEquals(pngBytes(), existing.getImageBytes());
        assertEquals("image/png", existing.getContentType());
        verify(listingImageRepository).save(existing);
    }

    @Test
    void uploadDeclaredContentTypeIsNormalisedToLowercase() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        when(listingImageRepository.findByListingIdAndPrimaryImageTrue(listingId))
                .thenReturn(Optional.empty());
        when(listingImageRepository.maxPosition(listingId)).thenReturn(-1);

        service.uploadImage(MERCHANT, listingId,
                new MockMultipartFile("image", "photo.PNG", "IMAGE/PNG", pngBytes()));

        ArgumentCaptor<ListingImage> saved = ArgumentCaptor.forClass(ListingImage.class);
        verify(listingImageRepository).save(saved.capture());
        assertEquals("image/png", saved.getValue().getContentType());
    }

    @Test
    void uploadWithoutAFileIs400ImageRequired() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);

        ApiException absent = assertThrows(ApiException.class,
                () -> service.uploadImage(MERCHANT, listingId, null));
        ApiException empty = assertThrows(ApiException.class,
                () -> service.uploadImage(MERCHANT, listingId,
                        new MockMultipartFile("image", "empty.png", "image/png", new byte[0])));

        assertEquals(HttpStatus.BAD_REQUEST, absent.status());
        assertEquals("image_required", absent.code());
        assertEquals("image_required", empty.code());
        verify(listingImageRepository, never()).save(any());
    }

    @Test
    void uploadWithDisallowedContentTypeIs400UnsupportedImageType() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.uploadImage(MERCHANT, listingId,
                        new MockMultipartFile("image", "page.html", "text/html", pngBytes())));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("unsupported_image_type", ex.code());
        verify(listingImageRepository, never()).save(any());
    }

    @Test
    void uploadWithBadMagicBytesIs400EvenUnderAnImageContentType() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);

        // The declared Content-Type is attacker-controlled: an HTML payload
        // smuggled under image/png must fail the signature sniff.
        ApiException ex = assertThrows(ApiException.class,
                () -> service.uploadImage(MERCHANT, listingId,
                        new MockMultipartFile("image", "fake.png", "image/png",
                                "<html><script>alert(1)</script>".getBytes())));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("unsupported_image_type", ex.code());
        verify(listingImageRepository, never()).save(any());
    }

    @Test
    void gifIsRejectedByContentTypeAndBySignature() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);

        // Honest GIF: allow-list refuses the declared type.
        ApiException declared = assertThrows(ApiException.class,
                () -> service.uploadImage(MERCHANT, listingId,
                        new MockMultipartFile("image", "anim.gif", "image/gif", gifBytes())));
        // Dishonest GIF under image/png: the signature sniff has no GIF branch.
        ApiException smuggled = assertThrows(ApiException.class,
                () -> service.uploadImage(MERCHANT, listingId,
                        new MockMultipartFile("image", "anim.png", "image/png", gifBytes())));

        assertEquals("unsupported_image_type", declared.code());
        assertEquals("unsupported_image_type", smuggled.code());
        verify(listingImageRepository, never()).save(any());
    }

    @Test
    void uploadOverTheTenMegabyteCapIs400ImageTooLarge() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        // Mocked file so the boundary is exercised without allocating 10 MB.
        MultipartFile oversize = mock(MultipartFile.class);
        when(oversize.isEmpty()).thenReturn(false);
        when(oversize.getSize()).thenReturn(ListingService.MAX_IMAGE_BYTES + 1);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.uploadImage(MERCHANT, listingId, oversize));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("image_too_large", ex.code());
        verify(listingImageRepository, never()).save(any());
    }

    @Test
    void uploadExactlyAtTheTenMegabyteCapPassesTheSizeGate() throws Exception {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        when(listingImageRepository.findByListingIdAndPrimaryImageTrue(listingId))
                .thenReturn(Optional.empty());
        when(listingImageRepository.maxPosition(listingId)).thenReturn(-1);
        MultipartFile atCap = mock(MultipartFile.class);
        when(atCap.isEmpty()).thenReturn(false);
        when(atCap.getSize()).thenReturn(ListingService.MAX_IMAGE_BYTES);
        when(atCap.getContentType()).thenReturn("image/png");
        when(atCap.getBytes()).thenReturn(pngBytes());

        service.uploadImage(MERCHANT, listingId, atCap);

        verify(listingImageRepository).save(any(ListingImage.class));
    }

    @Test
    void uploadToAnotherMerchantsListingIs403ListingNotOwned() {
        UUID listingId = UUID.randomUUID();
        Listing foreign = owned(listingId);
        foreign.setMerchantId(UUID.randomUUID());
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(foreign));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.uploadImage(MERCHANT, listingId,
                        new MockMultipartFile("image", "photo.png", "image/png", pngBytes())));

        assertEquals(HttpStatus.FORBIDDEN, ex.status());
        assertEquals("listing_not_owned", ex.code());
        verify(listingImageRepository, never()).save(any());
    }

    @Test
    void superAdminUploadsAndDeletesAnyMerchantsImage() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        when(listingImageRepository.findByListingIdAndPrimaryImageTrue(listingId))
                .thenReturn(Optional.empty());
        when(listingImageRepository.maxPosition(listingId)).thenReturn(-1);

        service.uploadImage(SUPER_ADMIN, listingId,
                new MockMultipartFile("image", "photo.png", "image/png", pngBytes()));
        verify(listingImageRepository).save(any(ListingImage.class));

        UUID imageId = UUID.randomUUID();
        when(listingImageRepository.findMetaByListingIdAndPrimaryImageTrue(listingId))
                .thenReturn(Optional.of(meta(imageId, listingId, true, 0)));
        service.deleteImage(SUPER_ADMIN, listingId);
        verify(listingImageRepository).deleteImageRow(imageId);
    }

    @Test
    void addImageAppendsNonPrimaryAfterTheLastPosition() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        when(listingImageRepository.countByListingId(listingId)).thenReturn(3L);
        when(listingImageRepository.maxPosition(listingId)).thenReturn(2);

        service.addImage(MERCHANT, listingId,
                new MockMultipartFile("image", "extra.png", "image/png", pngBytes()));

        ArgumentCaptor<ListingImage> saved = ArgumentCaptor.forClass(ListingImage.class);
        verify(listingImageRepository).save(saved.capture());
        assertFalse(saved.getValue().isPrimaryImage());
        assertEquals(3, saved.getValue().getPosition());
        verify(auditService).record(eq(AuditEventType.LISTING_IMAGE_ADDED),
                eq(MERCHANT.uuid()), eq(listingId.toString()), anyMap());
    }

    @Test
    void addImageIntoAnEmptyGalleryBecomesThePrimary() {
        // Gallery invariant: images present => one primary. Without this an
        // imageless listing grown via POST /images could never publish.
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        when(listingImageRepository.countByListingId(listingId)).thenReturn(0L);
        when(listingImageRepository.maxPosition(listingId)).thenReturn(-1);

        service.addImage(MERCHANT, listingId,
                new MockMultipartFile("image", "first.png", "image/png", pngBytes()));

        ArgumentCaptor<ListingImage> saved = ArgumentCaptor.forClass(ListingImage.class);
        verify(listingImageRepository).save(saved.capture());
        assertTrue(saved.getValue().isPrimaryImage());
    }

    @Test
    void addImageAtTheGalleryCapIs409ImageLimitReached() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        when(listingImageRepository.countByListingId(listingId))
                .thenReturn((long) ListingService.MAX_GALLERY_IMAGES);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.addImage(MERCHANT, listingId,
                        new MockMultipartFile("image", "extra.png", "image/png", pngBytes())));

        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("image_limit_reached", ex.code());
        verify(listingImageRepository, never()).save(any());
    }

    @Test
    void deleteGalleryImageOfAForeignImageIdIs404ImageNotFound() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        UUID imageId = UUID.randomUUID();
        when(listingImageRepository.findMetaByIdAndListingId(imageId, listingId))
                .thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.deleteGalleryImage(MERCHANT, listingId, imageId));

        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("image_not_found", ex.code());
        verify(listingImageRepository, never()).deleteImageRow(any());
    }

    @Test
    void deletingANonPrimaryImageNeverPromotes() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        UUID imageId = UUID.randomUUID();
        when(listingImageRepository.findMetaByIdAndListingId(imageId, listingId))
                .thenReturn(Optional.of(meta(imageId, listingId, false, 2)));

        service.deleteGalleryImage(MERCHANT, listingId, imageId);

        verify(listingImageRepository).deleteImageRow(imageId);
        verify(listingImageRepository, never())
                .findFirstByListingIdOrderByPositionAscCreatedAtAsc(any());
        verify(listingImageRepository, never()).markPrimary(any());
        verify(auditService).record(eq(AuditEventType.LISTING_IMAGE_DELETED),
                eq(MERCHANT.uuid()), eq(listingId.toString()), anyMap());
    }

    @Test
    void deletingThePrimaryPromotesTheLowestPositionSurvivor() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        UUID primaryId = UUID.randomUUID();
        UUID survivorId = UUID.randomUUID();
        when(listingImageRepository.findMetaByIdAndListingId(primaryId, listingId))
                .thenReturn(Optional.of(meta(primaryId, listingId, true, 0)));
        when(listingImageRepository.findFirstByListingIdOrderByPositionAscCreatedAtAsc(listingId))
                .thenReturn(Optional.of(meta(survivorId, listingId, false, 1)));

        service.deleteGalleryImage(MERCHANT, listingId, primaryId);

        // Delete FIRST, then promote — the partial unique index depends on it.
        InOrder inOrder = inOrder(listingImageRepository);
        inOrder.verify(listingImageRepository).deleteImageRow(primaryId);
        inOrder.verify(listingImageRepository).markPrimary(survivorId);
    }

    @Test
    void deletingTheLastImageLeavesTheGalleryEmpty() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        UUID primaryId = UUID.randomUUID();
        when(listingImageRepository.findMetaByIdAndListingId(primaryId, listingId))
                .thenReturn(Optional.of(meta(primaryId, listingId, true, 0)));
        when(listingImageRepository.findFirstByListingIdOrderByPositionAscCreatedAtAsc(listingId))
                .thenReturn(Optional.empty());

        service.deleteGalleryImage(MERCHANT, listingId, primaryId);

        verify(listingImageRepository).deleteImageRow(primaryId);
        verify(listingImageRepository, never()).markPrimary(any());
    }

    @Test
    void deletePrimaryEndpointIsANoOpWhenNoPrimaryExists() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        when(listingImageRepository.findMetaByListingIdAndPrimaryImageTrue(listingId))
                .thenReturn(Optional.empty());

        var response = service.deleteImage(MERCHANT, listingId);

        assertNull(response.imageUrl());
        verify(listingImageRepository, never()).deleteImageRow(any());
        verify(auditService, never()).record(eq(AuditEventType.LISTING_IMAGE_DELETED),
                any(), any(), anyMap());
    }

    @Test
    void setPrimarySwapsAtomicallyDemoteThenMark() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        UUID imageId = UUID.randomUUID();
        when(listingImageRepository.findMetaByIdAndListingId(imageId, listingId))
                .thenReturn(Optional.of(meta(imageId, listingId, false, 2)));

        service.setPrimaryImage(MERCHANT, listingId, imageId);

        // Demote FIRST, then mark — two primaries mid-transaction would trip
        // the partial unique index.
        InOrder inOrder = inOrder(listingImageRepository);
        inOrder.verify(listingImageRepository).demotePrimary(listingId);
        inOrder.verify(listingImageRepository).markPrimary(imageId);
        verify(auditService).record(eq(AuditEventType.LISTING_IMAGE_PRIMARY_CHANGED),
                eq(MERCHANT.uuid()), eq(listingId.toString()), anyMap());
    }

    @Test
    void setPrimaryOnTheCurrentPrimaryIsANoOp() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        UUID imageId = UUID.randomUUID();
        when(listingImageRepository.findMetaByIdAndListingId(imageId, listingId))
                .thenReturn(Optional.of(meta(imageId, listingId, true, 0)));

        service.setPrimaryImage(MERCHANT, listingId, imageId);

        verify(listingImageRepository, never()).demotePrimary(any());
        verify(listingImageRepository, never()).markPrimary(any());
    }

    @Test
    void setPrimaryOnAForeignImageIdIs404ImageNotFound() {
        UUID listingId = UUID.randomUUID();
        stubOwned(listingId);
        UUID imageId = UUID.randomUUID();
        when(listingImageRepository.findMetaByIdAndListingId(imageId, listingId))
                .thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.setPrimaryImage(MERCHANT, listingId, imageId));

        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("image_not_found", ex.code());
        verify(listingImageRepository, never()).markPrimary(any());
    }

    // ------------------------------------------------------------------
    // Delivery towns (V14)
    // ------------------------------------------------------------------

    private static ListingCreateRequest deliverable(List<DeliveryTownFee> towns) {
        return new ListingCreateRequest("Solar Lantern", null, null, null, null, null,
                2599L, 120, null, towns);
    }

    @Test
    void createSavesEachDeliveryTownWithItsFee() {
        service.create(MERCHANT, deliverable(List.of(
                new DeliveryTownFee("harare", 300L), new DeliveryTownFee(" Bulawayo ", 1200L))));

        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(saved.capture());
        ArgumentCaptor<List<ListingDeliveryTown>> rows = ArgumentCaptor.forClass(List.class);
        verify(deliveryTownRepository).saveAll(rows.capture());
        UUID id = saved.getValue().getId();
        assertEquals(List.of(new ListingDeliveryTown(id, "harare", 300),
                new ListingDeliveryTown(id, "bulawayo", 1200)), rows.getValue());
    }

    @Test
    void createWithNoTownsIsCollectionOnlyAndWritesNoCoverage() {
        service.create(MERCHANT, createReq("Solar Lantern", null, null));

        verify(deliveryTownRepository, never()).saveAll(any());
    }

    @Test
    void createWithAnUnknownTownIsRefusedBeforeTheListingExists() {
        ApiException ex = assertThrows(ApiException.class, () -> service.create(MERCHANT,
                deliverable(List.of(new DeliveryTownFee("johannesburg", 500L)))));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("unknown_town", ex.code());
        verify(listingRepository, never()).save(any());
        // Validation runs BEFORE the seller record is ensured, so a refused
        // first create never registers (and audits) a seller it rolls back.
        verify(sellerService, never()).ensureExists(any());
    }

    @Test
    void aTownNamedTwiceIsRefusedRatherThanPickingAFee() {
        ApiException ex = assertThrows(ApiException.class, () -> service.create(MERCHANT,
                deliverable(List.of(new DeliveryTownFee("harare", 300L),
                        new DeliveryTownFee("HARARE", 0L)))));

        assertEquals("duplicate_delivery_town", ex.code());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void updateWithoutTownsLeavesCoverageAlone() {
        UUID listingId = UUID.randomUUID();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(owned(listingId)));

        service.update(MERCHANT, listingId, updateReq("Lamp", null, null, 2399L, 5));

        verify(deliveryTownRepository, never()).deleteByListingId(any());
        verify(deliveryTownRepository, never()).saveAll(any());
    }

    @Test
    void updateWithTownsReplacesCoverageAndAnEmptyListClearsIt() {
        UUID listingId = UUID.randomUUID();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(owned(listingId)));

        service.update(MERCHANT, listingId, new ListingUpdateRequest("Lamp", null, null, null,
                null, null, 2399L, 5, List.of(new DeliveryTownFee("mutare", 800L))));
        service.update(MERCHANT, listingId, new ListingUpdateRequest("Lamp", null, null, null,
                null, null, 2399L, 5, List.of()));

        InOrder order = inOrder(deliveryTownRepository);
        order.verify(deliveryTownRepository).deleteByListingId(listingId);
        order.verify(deliveryTownRepository).saveAll(
                List.of(new ListingDeliveryTown(listingId, "mutare", 800)));
        order.verify(deliveryTownRepository).deleteByListingId(listingId);
        // The empty list wrote nothing back: the listing is collection-only now.
        verify(deliveryTownRepository).saveAll(any());
    }

    // ------------------------------------------------------------------
    // Product variants (V19): the editor's options, their stock and price
    // ------------------------------------------------------------------

    /** The Swagger "Cotton Crew Tee" and its options. */
    private static final UUID TEE = UUID.fromString("e3a91c57-2b4d-4f8e-9a16-7c5d0b2e8f41");
    private static final UUID M_BLACK = UUID.fromString("0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352");
    private static final UUID L_BLACK = UUID.fromString("1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463");
    private static final UUID XL_BLACK = UUID.fromString("2c8f4f3a-7e5d-40b9-af43-d6b9a1e3c574");
    private static final List<String> SIZE_COLOUR = List.of("Size", "Colour");

    /** A Black option of the given size, found by its values on a replace. */
    private static VariantRequest option(String size, Long priceCents, Integer stockQty) {
        return new VariantRequest(null, List.of(size, "Black"), priceCents, stockQty);
    }

    /** A Black option that names the existing option it keeps. */
    private static VariantRequest option(UUID id, String size, Long priceCents, Integer stockQty) {
        return new VariantRequest(id, List.of(size, "Black"), priceCents, stockQty);
    }

    private static ListingCreateRequest createTee(Integer stockQty, List<String> options,
                                                  List<VariantRequest> variants) {
        return new ListingCreateRequest("Cotton Crew Tee", "100% cotton, pre-shrunk", null,
                null, null, null, 1999L, stockQty, null, null, options, variants);
    }

    private static ListingUpdateRequest updateTee(long priceCents, Integer stockQty,
                                                  List<String> options, List<VariantRequest> variants) {
        return new ListingUpdateRequest("Cotton Crew Tee", "100% cotton, pre-shrunk", null,
                null, null, null, priceCents, stockQty, null, options, variants);
    }

    /**
     * The Tee as the database holds it: priced 19.99, options M/Black (4),
     * L/Black (0) and XL/Black at 22.99 (6), so a total of 10 on the listing
     * row. Loadable by id.
     */
    private Listing seedTee() {
        Listing tee = owned(TEE);
        tee.setTitle("Cotton Crew Tee");
        tee.setPriceCents(1999);
        tee.setHasVariants(true);
        tee.setOption1Name("Size");
        tee.setOption2Name("Colour");
        tee.setStockQty(10);
        seedOption(TEE, M_BLACK, "M", null, 4, 0);
        seedOption(TEE, L_BLACK, "L", null, 0, 1);
        seedOption(TEE, XL_BLACK, "XL", 2299L, 6, 2);
        stock.put(TEE, 10);
        when(listingRepository.findById(TEE)).thenReturn(Optional.of(tee));
        return tee;
    }

    private ListingVariant seedOption(UUID listingId, UUID id, String size, Long priceCents,
                                      int stockQty, int position) {
        Instant created = Instant.parse("2026-09-20T08:00:00Z");
        ListingVariant row = ListingVariant.builder()
                .id(id).listingId(listingId).priceCents(priceCents).stockQty(stockQty)
                .position(position).createdAt(created).updatedAt(created).version(0L)
                .build();
        row.setValues(size, "Black");
        optionRows.put(id, row);
        optionStock.put(id, stockQty);
        return row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> auditMetadata(AuditEventType type) {
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(type), eq(MERCHANT.uuid()), anyString(), meta.capture());
        return meta.getValue();
    }

    private static List<String> labels(ListingResponse response) {
        return response.variants().stream().map(ListingVariantResponse::label).toList();
    }

    // ---- create -------------------------------------------------------

    @Test
    @DisplayName("A listing created with options stores its axes, starts at the sum of their stock "
            + "and inserts the options after the listing row")
    @SuppressWarnings("unchecked")
    void createWithOptionsStoresTheAxesAndTheirSum() {
        // stockQty 120 in the body is ignored: a listing with options takes
        // its stock from them.
        ListingResponse response = service.create(MERCHANT, createTee(120, SIZE_COLOUR, List.of(
                option("M", null, 4), option("L", null, 0), option("XL", 2299L, 6))));

        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(saved.capture());
        Listing listing = saved.getValue();
        assertTrue(listing.isHasVariants());
        assertEquals("Size", listing.getOption1Name());
        assertEquals("Colour", listing.getOption2Name());
        assertEquals(1999L, listing.getPriceCents());
        assertEquals(10, listing.getStockQty());

        // The options reference the listing row, so they are written after it.
        InOrder order = inOrder(listingRepository, variantRepository);
        order.verify(listingRepository).save(listing);
        ArgumentCaptor<List<ListingVariant>> rows = ArgumentCaptor.forClass(List.class);
        order.verify(variantRepository).saveAll(rows.capture());
        List<ListingVariant> options = rows.getValue();
        assertEquals(List.of("M - Black", "L - Black", "XL - Black"),
                options.stream().map(ListingVariant::label).toList());
        assertEquals(List.of("m,black", "l,black", "xl,black"),
                options.stream().map(ListingVariant::getOptionKey).toList());
        assertEquals(java.util.Arrays.asList(null, null, 2299L),
                options.stream().map(ListingVariant::getPriceCents).toList());
        assertEquals(List.of(4, 0, 6), options.stream().map(ListingVariant::getStockQty).toList());
        assertEquals(List.of(0, 1, 2), options.stream().map(ListingVariant::getPosition).toList());
        assertTrue(options.stream().allMatch(row -> listing.getId().equals(row.getListingId())));
        // Inserted consistent: nothing to recompute, and a create announces no restock.
        verify(listingRepository, never()).recomputeStockTotal(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));

        Map<String, Object> audit = auditMetadata(AuditEventType.LISTING_CREATED);
        assertEquals(3, audit.get("variantCount"));
        assertEquals(10, audit.get("stockQty"));

        assertTrue(response.hasVariants());
        assertEquals(10, response.stockQty());
        assertEquals(2299L, response.maxPriceCents());
        assertEquals(List.of(new ListingOptionResponse("Size", List.of("M", "L", "XL")),
                new ListingOptionResponse("Colour", List.of("Black"))), response.options());
        assertEquals(List.of("M - Black", "L - Black", "XL - Black"), labels(response));
    }

    @Test
    @DisplayName("With the cell switch off, a create with options is 422 variants_disabled and "
            + "writes nothing, while a create without options still works")
    void createWithOptionsWhileTheSwitchIsOffIs422() {
        ListingService off = newService(false);

        ApiException ex = assertThrows(ApiException.class, () -> off.create(MERCHANT,
                createTee(null, SIZE_COLOUR, List.of(option("M", null, 4)))));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.status());
        assertEquals("variants_disabled", ex.code());
        assertEquals("Product options are not available on this marketplace yet", ex.getMessage());
        verify(listingRepository, never()).save(any());
        verify(variantRepository, never()).saveAll(any());
        // Refused before the seller record is ensured, like every other create refusal.
        verify(sellerService, never()).ensureExists(any());

        // The switch gates only the move INTO options.
        off.create(MERCHANT, createReq("Solar Lantern", null, null));
        verify(listingRepository).save(any());
    }

    @Test
    @DisplayName("A create without options needs stockQty: null variants and an empty list are "
            + "both 400 stock_required")
    void createWithoutOptionsOrStockIs400StockRequired() {
        for (List<VariantRequest> none : java.util.Arrays.asList(null, List.<VariantRequest>of())) {
            ApiException ex = assertThrows(ApiException.class,
                    () -> service.create(MERCHANT, createTee(null, null, none)));

            assertEquals(HttpStatus.BAD_REQUEST, ex.status());
            assertEquals("stock_required", ex.code());
            assertEquals("stockQty is required for a listing without variants", ex.getMessage());
        }
        verify(listingRepository, never()).save(any());
        verify(sellerService, never()).ensureExists(any());
    }

    @Test
    @DisplayName("options without variants is 400 options_without_variants, never silently dropped")
    void optionsWithoutVariantsIs400() {
        for (List<VariantRequest> none : java.util.Arrays.asList(null, List.<VariantRequest>of())) {
            ApiException ex = assertThrows(ApiException.class,
                    () -> service.create(MERCHANT, createTee(5, List.of("Size"), none)));

            assertEquals(HttpStatus.BAD_REQUEST, ex.status());
            assertEquals("options_without_variants", ex.code());
            assertEquals("options can only be sent together with variants", ex.getMessage());
        }
        verify(listingRepository, never()).save(any());
    }

    // ---- update -------------------------------------------------------

    @Test
    @DisplayName("Update with variants null KEEPS the options: no option write, stockQty ignored "
            + "(and not required), and the response carries the total the database holds")
    void updateWithVariantsNullKeepsTheOptions() {
        Listing tee = seedTee();
        // A stale in-memory total: the response must show what the recompute read back.
        tee.setStockQty(99);

        ListingResponse response = service.update(MERCHANT, TEE, updateTee(1999L, 500, null, null));
        service.update(MERCHANT, TEE, updateTee(1999L, null, null, null));

        verify(variantRepository, never()).saveAll(any());
        verify(variantRepository, never()).deleteAll(any());
        verify(variantRepository, never()).setStock(any(), any(), anyInt(), any());
        verify(listingRepository, never()).setPlainStock(any(), anyInt());
        assertTrue(tee.isHasVariants());
        assertEquals("Size", tee.getOption1Name());
        assertEquals("Colour", tee.getOption2Name());
        assertEquals(10, tee.getStockQty());
        assertEquals(10, response.stockQty());
        assertEquals(List.of("M - Black", "L - Black", "XL - Black"), labels(response));
        assertEquals(List.of(4, 0, 6), List.of(optionStock.get(M_BLACK), optionStock.get(L_BLACK),
                optionStock.get(XL_BLACK)));
        verify(eventPublisher, never()).publishEvent(any(Object.class));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(auditService, times(2)).record(eq(AuditEventType.LISTING_UPDATED),
                eq(MERCHANT.uuid()), eq(TEE.toString()), meta.capture());
        assertEquals(false, meta.getAllValues().get(0).get("variantsChanged"));
        assertEquals(3, meta.getAllValues().get(0).get("variantCount"));
        assertEquals(10, meta.getAllValues().get(0).get("stockQty"));
    }

    @Test
    @DisplayName("Update with variants [] and no stockQty is 400 stock_required and leaves the "
            + "options untouched")
    void removingEveryOptionWithoutStockIs400() {
        Listing tee = seedTee();

        ApiException ex = assertThrows(ApiException.class,
                () -> service.update(MERCHANT, TEE, updateTee(1999L, null, null, List.of())));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("stock_required", ex.code());
        assertTrue(tee.isHasVariants());
        verify(listingRepository, never()).save(any());
        verify(variantRepository, never()).deleteAll(any());
        verify(listingRepository, never()).setPlainStock(any(), anyInt());
        assertEquals(3, optionsOf(TEE).size());
    }

    @Test
    @DisplayName("Update with variants [] converts to a listing without options: every option "
            + "deleted, the axes cleared, and stockQty set as the plain stock")
    @SuppressWarnings("unchecked")
    void removingEveryOptionConvertsToPlain() {
        Listing tee = seedTee();

        ListingResponse response = service.update(MERCHANT, TEE, updateTee(1999L, 7, null, List.of()));

        assertFalse(tee.isHasVariants());
        assertNull(tee.getOption1Name());
        assertNull(tee.getOption2Name());
        // The flag is saved first, so the guarded plain set (has_variants = FALSE) applies.
        InOrder order = inOrder(listingRepository, variantRepository);
        order.verify(listingRepository).save(tee);
        ArgumentCaptor<List<ListingVariant>> removed = ArgumentCaptor.forClass(List.class);
        order.verify(variantRepository).deleteAll(removed.capture());
        order.verify(listingRepository).setPlainStock(TEE, 7);
        assertEquals(List.of(M_BLACK, L_BLACK, XL_BLACK),
                removed.getValue().stream().map(ListingVariant::getId).toList());
        verify(listingRepository, never()).recomputeStockTotal(any());

        assertEquals(7, tee.getStockQty());
        assertEquals(7, response.stockQty());
        assertFalse(response.hasVariants());
        assertEquals(List.of(), response.options());
        assertEquals(List.of(), response.variants());
        assertEquals(1999L, response.maxPriceCents());
        Map<String, Object> audit = auditMetadata(AuditEventType.LISTING_UPDATED);
        assertEquals(true, audit.get("variantsChanged"));
        assertEquals(0, audit.get("variantCount"));
    }

    @Test
    @DisplayName("A replace keeps options by id and by values, deletes exactly the unclaimed ones, "
            + "sets stock only where it was sent, and inserts the new ones")
    @SuppressWarnings("unchecked")
    void aReplaceKeepsByIdAndByValuesAndDeletesTheRest() {
        seedTee();
        ListingVariant m = optionRows.get(M_BLACK);
        ListingVariant l = optionRows.get(L_BLACK);
        ListingVariant xl = optionRows.get(XL_BLACK);

        ListingResponse response = service.update(MERCHANT, TEE, updateTee(1999L, null, SIZE_COLOUR,
                List.of(option(M_BLACK, "M", null, null),   // kept by id; no stockQty keeps its 4
                        option("XL", 2299L, 9),              // kept by its values; set to 9
                        option("S", null, 3))));             // new

        // L is the one existing option no entry claimed.
        ArgumentCaptor<List<ListingVariant>> removed = ArgumentCaptor.forClass(List.class);
        verify(variantRepository).deleteAll(removed.capture());
        assertEquals(List.of(l), removed.getValue());
        // Stock is set only on the kept option that sent one.
        verify(variantRepository, times(1)).setStock(any(), any(), anyInt(), any());
        verify(variantRepository).setStock(eq(XL_BLACK), eq(TEE), eq(9), any());
        verify(variantRepository, never()).setStock(eq(M_BLACK), any(), anyInt(), any());
        assertEquals(4, optionStock.get(M_BLACK));
        // ...and mirrored in memory, since the entity cannot write it.
        assertEquals(4, m.getStockQty());
        assertEquals(9, xl.getStockQty());
        assertEquals(0, m.getPosition());
        assertEquals(1, xl.getPosition());
        assertEquals(2299L, xl.getPriceCents());
        // The one new option.
        ArgumentCaptor<List<ListingVariant>> added = ArgumentCaptor.forClass(List.class);
        verify(variantRepository).saveAll(added.capture());
        assertEquals(1, added.getValue().size());
        ListingVariant s = added.getValue().get(0);
        assertEquals(TEE, s.getListingId());
        assertEquals("S - Black", s.label());
        assertEquals(3, s.getStockQty());
        assertEquals(2, s.getPosition());
        assertFalse(Set.of(M_BLACK, L_BLACK, XL_BLACK).contains(s.getId()));

        // Delete, then the kept rows' sets, then the inserts, and the total LAST.
        InOrder order = inOrder(variantRepository, listingRepository);
        order.verify(variantRepository).deleteAll(any());
        order.verify(variantRepository).setStock(eq(XL_BLACK), eq(TEE), eq(9), any());
        order.verify(variantRepository).saveAll(any());
        order.verify(listingRepository).recomputeStockTotal(TEE);

        // 4 (kept) + 9 + 3.
        assertEquals(16, response.stockQty());
        assertEquals(List.of("M - Black", "XL - Black", "S - Black"), labels(response));
        assertEquals(List.of(4, 9, 3),
                response.variants().stream().map(ListingVariantResponse::stockQty).toList());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
        Map<String, Object> audit = auditMetadata(AuditEventType.LISTING_UPDATED);
        assertEquals(true, audit.get("variantsChanged"));
        assertEquals(3, audit.get("variantCount"));
    }

    @Test
    @DisplayName("With the cell switch off, turning a listing without options into one with "
            + "options is 422 variants_disabled and writes nothing")
    void convertingAPlainListingWhileTheSwitchIsOffIs422() {
        ListingService off = newService(false);
        UUID listingId = UUID.randomUUID();
        Listing plain = owned(listingId);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(plain));

        ApiException ex = assertThrows(ApiException.class, () -> off.update(MERCHANT, listingId,
                updateTee(1000L, null, SIZE_COLOUR, List.of(option("M", null, 4)))));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.status());
        assertEquals("variants_disabled", ex.code());
        assertFalse(plain.isHasVariants());
        assertEquals("Old title", plain.getTitle());
        verify(listingRepository, never()).save(any());
        verify(variantRepository, never()).saveAll(any());
        verify(listingRepository, never()).setPlainStock(any(), anyInt());
    }

    @Test
    @DisplayName("With the cell switch off, a listing that already has options can still be "
            + "edited and can still be turned back into one without")
    void theSwitchNeverStrandsAListingThatAlreadyHasOptions() {
        ListingService off = newService(false);
        Listing tee = seedTee();

        off.update(MERCHANT, TEE, updateTee(1999L, null, SIZE_COLOUR, List.of(
                option(M_BLACK, "M", null, 5), option(XL_BLACK, "XL", 2299L, null))));
        assertTrue(tee.isHasVariants());
        assertEquals(11, tee.getStockQty());

        off.update(MERCHANT, TEE, updateTee(1999L, 3, null, List.of()));
        assertFalse(tee.isHasVariants());
        assertEquals(3, tee.getStockQty());
    }

    @Test
    @DisplayName("The editor takes the listing's row lock BEFORE it loads the entity, and only "
            + "after the caller's merchant scope is checked")
    void theRowLockIsTakenBeforeTheListingIsLoaded() {
        UUID listingId = UUID.randomUUID();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(owned(listingId)));

        service.update(MERCHANT, listingId, updateReq("Lamp", null, null, 2399L, 5));

        InOrder order = inOrder(listingRepository);
        order.verify(listingRepository).lockForStock(listingId);
        order.verify(listingRepository).findById(listingId);

        // An unscoped caller is refused before any row is locked.
        assertThrows(ApiException.class, () -> service.update(callerWithMerchantClaim(null),
                listingId, updateReq("Lamp", null, null, 2399L, 5)));
        verify(listingRepository, times(1)).lockForStock(any());
    }

    @Test
    @DisplayName("A missing listing is 404 listing_not_found off the seller read: no lock, no entity load")
    void missingListingIs404BeforeTheLock() {
        UUID listingId = UUID.randomUUID();
        org.mockito.Mockito.doReturn(null).when(listingRepository).merchantIdOf(listingId);

        ApiException ex = assertThrows(ApiException.class, () -> service.update(MERCHANT, listingId,
                updateReq("Lamp", null, null, 2399L, 5)));

        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("listing_not_found", ex.code());
        verify(listingRepository, never()).lockForStock(any());
        verify(listingRepository, never()).findById(any());
    }

    @Test
    @DisplayName("A listing deleted between the seller read and the lock is 404 before the entity is loaded")
    void noRowToLockIs404() {
        UUID listingId = UUID.randomUUID();
        org.mockito.Mockito.doReturn(MERCHANT_ID).when(listingRepository).merchantIdOf(listingId);
        when(listingRepository.lockForStock(listingId)).thenReturn(null);

        ApiException ex = assertThrows(ApiException.class, () -> service.update(MERCHANT, listingId,
                updateReq("Lamp", null, null, 2399L, 5)));

        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("listing_not_found", ex.code());
        verify(listingRepository, never()).findById(any());
    }

    @Test
    @DisplayName("ListingRestocked is published from the stock read UNDER THE LOCK: 0 there and "
            + "more after is a restock, whatever the loaded entity claimed")
    void restockIsPublishedFromTheLockedBefore() {
        UUID soldOut = UUID.randomUUID();
        when(listingRepository.findById(soldOut)).thenReturn(Optional.of(owned(soldOut))); // entity: 10
        stock.put(soldOut, 0); // the locked row: sold out

        ListingResponse response = service.update(MERCHANT, soldOut,
                updateReq("Lamp", null, null, 1000L, 25));

        verify(eventPublisher, times(1)).publishEvent(new ListingRestocked(soldOut));
        assertEquals(25, response.stockQty());

        // The other way round: the entity claims 0, but the locked row held 5.
        UUID inStock = UUID.randomUUID();
        Listing staleZero = owned(inStock);
        staleZero.setStockQty(0);
        when(listingRepository.findById(inStock)).thenReturn(Optional.of(staleZero));
        stock.put(inStock, 5);

        service.update(MERCHANT, inStock, updateReq("Lamp", null, null, 1000L, 25));

        verify(eventPublisher, never()).publishEvent(new ListingRestocked(inStock));
        verify(eventPublisher, times(1)).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("Raising priceCents above an option's own price with variants null is 400 "
            + "variant_price_below_listing_price, and nothing is written")
    void raisingThePriceAboveAnOptionsOwnPriceIs400() {
        Listing tee = seedTee();

        ApiException ex = assertThrows(ApiException.class,
                () -> service.update(MERCHANT, TEE, updateTee(2500L, null, null, null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("variant_price_below_listing_price", ex.code());
        assertEquals("The option XL - Black costs less than priceCents - make priceCents the lowest "
                + "option price", ex.getMessage());
        assertEquals(1999L, tee.getPriceCents());
        assertEquals(2299L, optionRows.get(XL_BLACK).getPriceCents());
        verify(listingRepository, never()).save(any());
    }

    @Test
    @DisplayName("Raising priceCents to exactly an option's own price clears that own price, so "
            + "the option follows the listing price from then on")
    void raisingThePriceToAnOptionsOwnPriceClearsIt() {
        Listing tee = seedTee();

        ListingResponse response = service.update(MERCHANT, TEE, updateTee(2299L, null, null, null));

        assertEquals(2299L, tee.getPriceCents());
        assertNull(optionRows.get(XL_BLACK).getPriceCents());
        assertTrue(response.variants().stream().allMatch(v -> v.priceOverrideCents() == null));
        assertTrue(response.variants().stream().allMatch(v -> v.priceCents() == 2299L));
        assertEquals(2299L, response.maxPriceCents());
        verify(variantRepository, never()).saveAll(any());
    }

    // ---- the one-option quick restock -----------------------------------

    @Test
    @DisplayName("Setting an option's stock on a listing without options is 404 variant_not_found")
    void variantStockOnAPlainListingIs404() {
        UUID listingId = UUID.randomUUID();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(owned(listingId)));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.setVariantStock(MERCHANT, listingId, M_BLACK, 12));

        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("variant_not_found", ex.code());
        assertEquals("Variant not found", ex.getMessage());
        verify(variantRepository, never()).setStock(any(), any(), anyInt(), any());
        verify(listingRepository, never()).save(any());
        verify(auditService, never()).record(eq(AuditEventType.LISTING_VARIANT_STOCK_SET),
                any(), any(), anyMap());
    }

    @Test
    @DisplayName("Setting the stock of an id that is not an option of THIS listing (the statement "
            + "updates no row) is 404 variant_not_found and recomputes nothing")
    void variantStockForAnotherListingsOptionIs404() {
        seedTee();
        UUID otherListing = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        seedOption(otherListing, foreign, "M", null, 1, 0);

        for (UUID variantId : List.of(foreign, UUID.randomUUID())) {
            ApiException ex = assertThrows(ApiException.class,
                    () -> service.setVariantStock(MERCHANT, TEE, variantId, 12));

            assertEquals(HttpStatus.NOT_FOUND, ex.status());
            assertEquals("variant_not_found", ex.code());
        }
        assertEquals(1, optionStock.get(foreign));
        verify(listingRepository, never()).recomputeStockTotal(any());
        verify(listingRepository, never()).save(any());
        verify(auditService, never()).record(eq(AuditEventType.LISTING_VARIANT_STOCK_SET),
                any(), any(), anyMap());
    }

    @Test
    @DisplayName("Setting one option's stock locks the listing first, recomputes the total, audits "
            + "LISTING_VARIANT_STOCK_SET and returns the new total")
    void variantStockSetRecomputesAuditsAndReturnsTheTotal() {
        Listing tee = seedTee();

        ListingResponse response = service.setVariantStock(MERCHANT, TEE, L_BLACK, 12);

        InOrder order = inOrder(listingRepository, variantRepository);
        order.verify(listingRepository).lockForStock(TEE);
        order.verify(listingRepository).findById(TEE);
        order.verify(variantRepository).setStock(eq(L_BLACK), eq(TEE), eq(12), any());
        order.verify(listingRepository).recomputeStockTotal(TEE);
        // The other options - and the reservations riding on them - are left alone.
        verify(variantRepository, times(1)).setStock(any(), any(), anyInt(), any());
        assertEquals(4, optionStock.get(M_BLACK));
        assertEquals(6, optionStock.get(XL_BLACK));

        // 4 + 12 + 6.
        assertEquals(22, response.stockQty());
        assertEquals(22, tee.getStockQty());
        Map<String, Object> audit = auditMetadata(AuditEventType.LISTING_VARIANT_STOCK_SET);
        assertEquals(Map.of("merchantId", MERCHANT_ID.toString(), "variantId", L_BLACK.toString(),
                "stockQty", 12), audit);
        // The total was 10 before: not a restock.
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("Setting one option's stock on a listing whose options were ALL sold out "
            + "publishes one ListingRestocked for the listing")
    void variantStockSetFromATotalOfZeroIsARestock() {
        seedTee();
        optionStock.put(M_BLACK, 0);
        optionStock.put(XL_BLACK, 0);
        stock.put(TEE, 0);

        ListingResponse response = service.setVariantStock(MERCHANT, TEE, L_BLACK, 2);

        verify(eventPublisher, times(1)).publishEvent(new ListingRestocked(TEE));
        assertEquals(2, response.stockQty());
    }

    @Test
    @DisplayName("Setting one option's stock so the options add up to more than 1000000 is 400 "
            + "stock_out_of_range and audits nothing")
    void variantStockSetAboveTheTotalBoundIs400() {
        seedTee();

        // 4 + 999991 + 6 = 1000001.
        ApiException ex = assertThrows(ApiException.class,
                () -> service.setVariantStock(MERCHANT, TEE, L_BLACK, 999_991));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("stock_out_of_range", ex.code());
        assertEquals("The options' stock adds up to more than 1000000", ex.getMessage());
        verify(auditService, never()).record(eq(AuditEventType.LISTING_VARIANT_STOCK_SET),
                any(), any(), anyMap());
    }

    // ---- publish --------------------------------------------------------

    @Test
    @DisplayName("A listing with options whose total is 0 can still be published - no new "
            + "publish rule, exactly as for a listing without options - and no stock moves")
    void aListingWithOptionsAtZeroCanBePublished() {
        Listing tee = seedTee();
        tee.setStockQty(0);
        optionStock.replaceAll((id, qty) -> 0);
        stock.put(TEE, 0);
        when(listingImageRepository.existsByListingIdAndPrimaryImageTrue(TEE)).thenReturn(true);

        ListingResponse response = service.changeStatus(MERCHANT, TEE,
                new ListingStatusRequest(ListingStatus.ACTIVE));

        assertEquals(ListingStatus.ACTIVE, tee.getStatus());
        assertEquals(ListingStatus.ACTIVE, response.status());
        assertEquals(0, response.stockQty());
        assertTrue(response.hasVariants());
        verify(listingRepository, never()).recomputeStockTotal(any());
        verify(listingRepository, never()).setPlainStock(any(), anyInt());
        verify(variantRepository, never()).setStock(any(), any(), anyInt(), any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }
}
