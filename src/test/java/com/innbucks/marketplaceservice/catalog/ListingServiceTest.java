package com.innbucks.marketplaceservice.catalog;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.dto.ListingCreateRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingStatusRequest;
import com.innbucks.marketplaceservice.catalog.dto.ListingUpdateRequest;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link ListingService}: merchant scope comes
 * from the JWT (missing/malformed = 403 {@code merchant_scope_missing}),
 * ownership is enforced on every row touch (403 {@code listing_not_owned}),
 * the per-merchant row cap holds, all free text passes the jsoup sanitizer
 * before storage, and the stored currency is ALWAYS the cell currency.
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
    private AuditService auditService;
    private SimpleMeterRegistry registry;
    private ListingService service;

    @BeforeEach
    void setUp() {
        listingRepository = mock(ListingRepository.class);
        auditService = mock(AuditService.class);
        registry = new SimpleMeterRegistry();
        service = new ListingService(listingRepository, auditService,
                new MarketplaceMetrics(registry), "USD", MAX_PER_MERCHANT);
    }

    private static ListingCreateRequest createReq(String title, String description,
                                                  String category) {
        return new ListingCreateRequest(title, description, category, 2599L, 120, null);
    }

    private static Listing owned(UUID listingId) {
        Instant now = Instant.now();
        return Listing.builder()
                .id(listingId).merchantId(MERCHANT_ID).title("Old title")
                .description("Old description").category("old")
                .priceCents(1000).currency("USD").stockQty(10)
                .status(ListingStatus.DRAFT).createdAt(now).updatedAt(now)
                .build();
    }

    private static AuthenticatedUser callerWithMerchantClaim(String merchantClaim) {
        return new AuthenticatedUser(UUID.randomUUID().toString(),
                Set.of("MERCHANT_ADMIN"), merchantClaim, null, null, "ZW");
    }

    // ------------------------------------------------------------------
    // Create: scope, sanitization, currency
    // ------------------------------------------------------------------

    @Test
    void createSanitizesFreeTextAppliesJwtScopeAndCellCurrency() {
        service.create(MERCHANT, createReq(
                "  <b>Solar Lantern</b> 20W  ",
                "Portable <i>bright</i> lantern",
                "<u>home</u>"));

        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(saved.capture());
        Listing listing = saved.getValue();
        // HTML stripped, entities plain, whitespace trimmed.
        assertEquals("Solar Lantern 20W", listing.getTitle());
        assertEquals("Portable bright lantern", listing.getDescription());
        assertEquals("home", listing.getCategory());
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
        assertNull(saved.getValue().getCategory());
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
                        new ListingUpdateRequest("Title", null, null, 1000L, 5)));

        assertEquals(HttpStatus.FORBIDDEN, ex.status());
        assertEquals("merchant_scope_missing", ex.code());
        verify(listingRepository, never()).findById(any());
    }

    // ------------------------------------------------------------------
    // Per-merchant cap
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Create with inline image (the multipart one-shot variant)
    // ------------------------------------------------------------------

    @Test
    void createWithInlineImageStoresBytesInTheSameInsert() {
        service.create(MERCHANT, createReq("Solar Lantern", "desc", "home"),
                new MockMultipartFile("image", "photo.png", "image/png", pngBytes()));

        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(saved.capture());
        assertArrayEquals(pngBytes(), saved.getValue().getImageBytes());
        assertEquals("image/png", saved.getValue().getImageContentType());
        assertTrue(saved.getValue().hasImage());
    }

    @Test
    void createWithAnInvalidImageRefusesTheWholeCreate() {
        // Atomicity: a bad file must never leave an imageless half-created
        // listing behind — validation runs BEFORE the insert.
        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(MERCHANT, createReq("Solar Lantern", "desc", "home"),
                        new MockMultipartFile("image", "fake.png", "image/png",
                                "not-an-image".getBytes())));

        assertEquals("unsupported_image_type", ex.code());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void createWithAnExplicitlyEmptyImagePartIsImageRequired() {
        // Only an ABSENT part means "no image" — a sent-but-empty part is a
        // client bug and refuses the create.
        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(MERCHANT, createReq("Solar Lantern", "desc", "home"),
                        new MockMultipartFile("image", "empty.png", "image/png", new byte[0])));

        assertEquals("image_required", ex.code());
        verify(listingRepository, never()).save(any());
    }

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
                        new ListingUpdateRequest("New title", null, null, 1000L, 5)));

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
                        new ListingUpdateRequest("New title", null, null, 1000L, 5)));

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
                "Now <b>brighter</b>", "garden", 2399L, 150));

        assertEquals("Nice lamp", listing.getTitle());
        assertEquals("Now brighter", listing.getDescription());
        assertEquals("garden", listing.getCategory());
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
    void changeStatusAppliesAndAuditsFromAndTo() {
        UUID listingId = UUID.randomUUID();
        Listing listing = owned(listingId);
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(listing));

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

    // ------------------------------------------------------------------
    // Server-side range re-checks (defense against non-HTTP callers)
    // ------------------------------------------------------------------

    @Test
    void priceOutOfRangeIsRejected() {
        ApiException zero = assertThrows(ApiException.class, () -> service.create(MERCHANT,
                new ListingCreateRequest("Solar Lantern", null, null, 0L, 10, null)));
        ApiException over = assertThrows(ApiException.class, () -> service.create(MERCHANT,
                new ListingCreateRequest("Solar Lantern", null, null, 100_000_001L, 10, null)));

        assertEquals(HttpStatus.BAD_REQUEST, zero.status());
        assertEquals("price_out_of_range", zero.code());
        assertEquals("price_out_of_range", over.code());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void stockOutOfRangeIsRejected() {
        ApiException negative = assertThrows(ApiException.class, () -> service.create(MERCHANT,
                new ListingCreateRequest("Solar Lantern", null, null, 1000L, -1, null)));
        ApiException over = assertThrows(ApiException.class, () -> service.create(MERCHANT,
                new ListingCreateRequest("Solar Lantern", null, null, 1000L, 1_000_001, null)));

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
                "Solar Lantern", null, null, 2599L, 120, MERCHANT_ID));

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
                        "Solar Lantern", null, null, 2599L, 120, UUID.randomUUID())));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.status());
        assertEquals("merchant_scope_mismatch", ex.code());
        verify(listingRepository, never()).save(any());
    }

    @Test
    void merchantAdminSendingTheirOwnMerchantIdIsAccepted() {
        service.create(MERCHANT, new ListingCreateRequest(
                "Solar Lantern", null, null, 2599L, 120, MERCHANT_ID));

        ArgumentCaptor<Listing> saved = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(saved.capture());
        assertEquals(MERCHANT_ID, saved.getValue().getMerchantId());
    }

    @Test
    void superAdminUpdatesAnotherMerchantsListingWithoutAMerchantClaim() {
        UUID listingId = UUID.randomUUID();
        when(listingRepository.findById(listingId)).thenReturn(Optional.of(owned(listingId)));

        service.update(SUPER_ADMIN, listingId,
                new ListingUpdateRequest("New title", null, null, 1000L, 5));

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
    // Listing image: upload validation (event-service banner discipline),
    // delete, ownership
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
    void uploadStoresBytesAndContentTypeAndExposesTheImageUrl() {
        UUID listingId = UUID.randomUUID();
        Listing listing = stubOwned(listingId);

        var response = service.uploadImage(MERCHANT, listingId,
                new MockMultipartFile("image", "photo.png", "image/png", pngBytes()));

        assertArrayEquals(pngBytes(), listing.getImageBytes());
        assertEquals("image/png", listing.getImageContentType());
        assertEquals("/marketplace/catalog/" + listingId + "/image", response.imageUrl());
        verify(listingRepository).save(listing);
        verify(auditService).record(eq(AuditEventType.LISTING_IMAGE_UPDATED),
                eq(MERCHANT.uuid()), eq(listingId.toString()), anyMap());
    }

    @Test
    void uploadDeclaredContentTypeIsNormalisedToLowercase() {
        UUID listingId = UUID.randomUUID();
        Listing listing = stubOwned(listingId);

        service.uploadImage(MERCHANT, listingId,
                new MockMultipartFile("image", "photo.PNG", "IMAGE/PNG", pngBytes()));

        assertEquals("image/png", listing.getImageContentType());
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
        verify(listingRepository, never()).save(any());
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
        verify(listingRepository, never()).save(any());
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
        verify(listingRepository, never()).save(any());
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
        verify(listingRepository, never()).save(any());
    }

    @Test
    void uploadOverTheTenMegabyteCapIs400ImageTooLarge() throws Exception {
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
        verify(listingRepository, never()).save(any());
    }

    @Test
    void uploadExactlyAtTheTenMegabyteCapPassesTheSizeGate() throws Exception {
        UUID listingId = UUID.randomUUID();
        Listing listing = stubOwned(listingId);
        MultipartFile atCap = mock(MultipartFile.class);
        when(atCap.isEmpty()).thenReturn(false);
        when(atCap.getSize()).thenReturn(ListingService.MAX_IMAGE_BYTES);
        when(atCap.getContentType()).thenReturn("image/png");
        when(atCap.getBytes()).thenReturn(pngBytes());

        service.uploadImage(MERCHANT, listingId, atCap);

        assertEquals("image/png", listing.getImageContentType());
        verify(listingRepository).save(listing);
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
        assertNull(foreign.getImageBytes());
    }

    @Test
    void superAdminUploadsAndDeletesAnyMerchantsImage() {
        UUID listingId = UUID.randomUUID();
        Listing listing = stubOwned(listingId);

        service.uploadImage(SUPER_ADMIN, listingId,
                new MockMultipartFile("image", "photo.png", "image/png", pngBytes()));
        assertEquals("image/png", listing.getImageContentType());

        service.deleteImage(SUPER_ADMIN, listingId);
        assertNull(listing.getImageBytes());
        assertNull(listing.getImageContentType());
    }

    @Test
    void deleteImageClearsBothColumnsAndAudits() {
        UUID listingId = UUID.randomUUID();
        Listing listing = stubOwned(listingId);
        listing.setImageBytes(pngBytes());
        listing.setImageContentType("image/png");

        var response = service.deleteImage(MERCHANT, listingId);

        assertNull(listing.getImageBytes());
        assertNull(listing.getImageContentType());
        assertNull(response.imageUrl());
        verify(listingRepository).save(listing);
        verify(auditService).record(eq(AuditEventType.LISTING_IMAGE_DELETED),
                eq(MERCHANT.uuid()), eq(listingId.toString()), anyMap());
    }
}
