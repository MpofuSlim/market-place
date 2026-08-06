package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.ListingImageRepository.ImageMeta;
import com.innbucks.marketplaceservice.catalog.dto.ListingCreateRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingStatusRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingUpdateRequest;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
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
 * is preserved by every image mutation.
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
    private ListingImageRepository listingImageRepository;
    private CategoryRepository categoryRepository;
    private AuditService auditService;
    private SimpleMeterRegistry registry;
    private ListingService service;

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        listingImageRepository = mock(ListingImageRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        auditService = mock(AuditService.class);
        registry = new SimpleMeterRegistry();
        // Taxonomy accepts everything unless a test narrows it — the
        // unknown-category test re-stubs the specific code to false.
        when(categoryRepository.existsById(anyString())).thenReturn(true);
        service = new ListingService(listingRepository, listingImageRepository,
                categoryRepository,
                new ListingViewAssembler(listingImageRepository, categoryRepository),
                auditService, new MarketplaceMetrics(registry),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                "USD", MAX_PER_MERCHANT);
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
}
