package com.innbucks.marketplaceservice.review;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.review.dto.ReviewRequest;
import com.innbucks.marketplaceservice.review.dto.ReviewResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
 * Pure-Mockito unit tests for {@link ReviewService}: the verified-purchase
 * gate (no PAID order containing the listing = 403, nothing persisted), the
 * one-review-per-buyer rule, the ATOMIC aggregate discipline (bulk-update
 * deltas, never entity writes), comment sanitization, delete authorization
 * (author or SUPER_ADMIN only), and the stable anonymized handle.
 */
class ReviewServiceTest {

    private static final UUID LISTING_ID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID BUYER_UUID = UUID.randomUUID();
    private static final AuthenticatedUser BUYER = new AuthenticatedUser(
            BUYER_UUID.toString(), Set.of("CUSTOMER"), null, null, "+263771234567", "ZW");
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("SUPER_ADMIN"), null, null, null, null);

    private ListingReviewRepository reviewRepository;
    private ListingRepository listingRepository;
    private MarketOrderRepository orderRepository;
    private AuditService auditService;
    private SimpleMeterRegistry registry;
    private ReviewService service;

    @BeforeEach
    void setUp() {
        reviewRepository = mock(ListingReviewRepository.class);
        listingRepository = mock(ListingRepository.class);
        orderRepository = mock(MarketOrderRepository.class);
        auditService = mock(AuditService.class);
        registry = new SimpleMeterRegistry();
        service = new ReviewService(reviewRepository, listingRepository, orderRepository,
                auditService, new MarketplaceMetrics(registry));
        when(listingRepository.findById(LISTING_ID)).thenReturn(Optional.of(listing()));
        when(reviewRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Listing listing() {
        Instant now = Instant.now();
        return Listing.builder()
                .id(LISTING_ID).merchantId(MERCHANT_ID).title("Solar Lantern 20W")
                .categoryCode("other").priceCents(1550).currency("USD").stockQty(10)
                .status(ListingStatus.ACTIVE).createdAt(now).updatedAt(now)
                .build();
    }

    private void buyerHasPaidOrder() {
        when(orderRepository.findPaidOrderIdsContainingListing(
                eq(BUYER_UUID), eq(LISTING_ID), any(Pageable.class)))
                .thenReturn(List.of(ORDER_ID));
    }

    private static ListingReview existing(int rating) {
        Instant now = Instant.now();
        return ListingReview.builder()
                .id(UUID.randomUUID()).listingId(LISTING_ID).merchantId(MERCHANT_ID)
                .buyerUuid(BUYER_UUID).orderId(ORDER_ID).rating(rating)
                .comment("old").createdAt(now).updatedAt(now)
                .build();
    }

    // ------------------------------------------------------------------
    // Create: THE verified-purchase gate
    // ------------------------------------------------------------------

    @Test
    void createWithoutPaidOrderIs403AndPersistsNothing() {
        when(orderRepository.findPaidOrderIdsContainingListing(
                eq(BUYER_UUID), eq(LISTING_ID), any(Pageable.class)))
                .thenReturn(List.of());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(BUYER, LISTING_ID, new ReviewRequest(5, "great")));

        assertEquals(HttpStatus.FORBIDDEN, ex.status());
        assertEquals("review_requires_purchase", ex.code());
        verify(reviewRepository, never()).saveAndFlush(any());
        verify(listingRepository, never()).adjustRatingAggregates(any(), eq(5L), eq(1));
        assertEquals(1.0, registry.counter("marketplace.reviews",
                "outcome", "rejected_unverified").count());
    }

    @Test
    void createStoresQualifyingOrderSanitizesCommentAndAdjustsAggregatesAtomically() {
        buyerHasPaidOrder();

        ReviewResponse response = service.create(BUYER, LISTING_ID,
                new ReviewRequest(4, "  Nice <script>alert(1)</script> lantern  "));

        ArgumentCaptor<ListingReview> saved = ArgumentCaptor.forClass(ListingReview.class);
        verify(reviewRepository).saveAndFlush(saved.capture());
        assertEquals(ORDER_ID, saved.getValue().getOrderId());
        assertEquals(MERCHANT_ID, saved.getValue().getMerchantId());
        assertEquals(BUYER_UUID, saved.getValue().getBuyerUuid());
        assertEquals("Nice  lantern", saved.getValue().getComment());
        assertEquals(4, response.rating());
        // The aggregate moves via the bulk UPDATE — the atomic path.
        verify(listingRepository).adjustRatingAggregates(LISTING_ID, 4, 1);
        verify(auditService).record(eq(AuditEventType.REVIEW_CREATED), eq(BUYER.uuid()),
                any(), anyMap());
        assertEquals(1.0, registry.counter("marketplace.reviews", "outcome", "created").count());
    }

    @Test
    void duplicateReviewIs409() {
        buyerHasPaidOrder();
        when(reviewRepository.existsByListingIdAndBuyerUuid(LISTING_ID, BUYER_UUID))
                .thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(BUYER, LISTING_ID, new ReviewRequest(5, null)));

        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("review_already_exists", ex.code());
        assertEquals(1.0, registry.counter("marketplace.reviews", "outcome", "duplicate").count());
    }

    @Test
    void unknownListingIs404BeforeAnyOrderLookup() {
        UUID unknown = UUID.randomUUID();
        when(listingRepository.findById(unknown)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(BUYER, unknown, new ReviewRequest(5, null)));

        assertEquals("listing_not_found", ex.code());
        verify(orderRepository, never()).findPaidOrderIdsContainingListing(any(), any(), any());
    }

    @Test
    void outOfRangeRatingIs400EvenWithoutBeanValidation() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(BUYER, LISTING_ID, new ReviewRequest(6, null)));
        assertEquals("invalid_rating", ex.code());
    }

    // ------------------------------------------------------------------
    // Edit: aggregates absorb the DELTA
    // ------------------------------------------------------------------

    @Test
    void updateMineAdjustsAggregateByDeltaOnly() {
        when(reviewRepository.findByListingIdAndBuyerUuid(LISTING_ID, BUYER_UUID))
                .thenReturn(Optional.of(existing(5)));

        ReviewResponse response = service.updateMine(BUYER, LISTING_ID,
                new ReviewRequest(2, "worse than expected"));

        assertEquals(2, response.rating());
        verify(listingRepository).adjustRatingAggregates(LISTING_ID, -3, 0);
        verify(auditService).record(eq(AuditEventType.REVIEW_UPDATED), eq(BUYER.uuid()),
                any(), anyMap());
    }

    @Test
    void updateMineWithUnchangedRatingSkipsTheAggregateUpdate() {
        when(reviewRepository.findByListingIdAndBuyerUuid(LISTING_ID, BUYER_UUID))
                .thenReturn(Optional.of(existing(4)));

        service.updateMine(BUYER, LISTING_ID, new ReviewRequest(4, "still fine"));

        verify(listingRepository, never()).adjustRatingAggregates(
                any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void updateMineWithoutExistingReviewIs404() {
        when(reviewRepository.findByListingIdAndBuyerUuid(LISTING_ID, BUYER_UUID))
                .thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class,
                () -> service.updateMine(BUYER, LISTING_ID, new ReviewRequest(3, null)));
        assertEquals("review_not_found", ex.code());
    }

    // ------------------------------------------------------------------
    // Delete: author or SUPER_ADMIN
    // ------------------------------------------------------------------

    @Test
    void authorDeleteDecrementsAggregatesAndAuditsNonAdminRemoval() {
        ListingReview review = existing(5);
        when(reviewRepository.findByIdAndListingId(review.getId(), LISTING_ID))
                .thenReturn(Optional.of(review));

        service.delete(BUYER, LISTING_ID, review.getId());

        verify(reviewRepository).delete(review);
        verify(listingRepository).adjustRatingAggregates(LISTING_ID, -5, -1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(auditService).record(eq(AuditEventType.REVIEW_DELETED), eq(BUYER.uuid()),
                eq(review.getId().toString()), metadata.capture());
        assertEquals(false, metadata.getValue().get("adminRemoval"));
    }

    @Test
    void superAdminDeleteBypassesAuthorshipAndAuditsAdminRemoval() {
        ListingReview review = existing(3);
        when(reviewRepository.findByIdAndListingId(review.getId(), LISTING_ID))
                .thenReturn(Optional.of(review));

        service.delete(ADMIN, LISTING_ID, review.getId());

        verify(reviewRepository).delete(review);
        verify(listingRepository).adjustRatingAggregates(LISTING_ID, -3, -1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(auditService).record(eq(AuditEventType.REVIEW_DELETED), eq(ADMIN.uuid()),
                eq(review.getId().toString()), metadata.capture());
        assertEquals(true, metadata.getValue().get("adminRemoval"));
    }

    @Test
    void strangerDeleteIs403AndNothingIsDeleted() {
        ListingReview review = existing(3);
        when(reviewRepository.findByIdAndListingId(review.getId(), LISTING_ID))
                .thenReturn(Optional.of(review));
        AuthenticatedUser stranger = new AuthenticatedUser(UUID.randomUUID().toString(),
                Set.of("CUSTOMER"), null, null, null, "ZW");

        ApiException ex = assertThrows(ApiException.class,
                () -> service.delete(stranger, LISTING_ID, review.getId()));

        assertEquals(HttpStatus.FORBIDDEN, ex.status());
        assertEquals("review_not_owned", ex.code());
        verify(reviewRepository, never()).delete(any(ListingReview.class));
    }

    // ------------------------------------------------------------------
    // Aggregates + anonymized handle
    // ------------------------------------------------------------------

    @Test
    void merchantRatingIsNullWhenNoReviewsAndOneDecimalOtherwise() {
        when(reviewRepository.countByMerchantId(MERCHANT_ID)).thenReturn(0L);
        assertNull(service.merchantRating(MERCHANT_ID).ratingAvg());
        assertEquals(0, service.merchantRating(MERCHANT_ID).reviewCount());

        when(reviewRepository.countByMerchantId(MERCHANT_ID)).thenReturn(3L);
        when(reviewRepository.sumRatingByMerchantId(MERCHANT_ID)).thenReturn(11L);
        // 11/3 = 3.666... -> 3.7 at one decimal.
        assertEquals(3.7, service.merchantRating(MERCHANT_ID).ratingAvg());
    }

    @Test
    void anonymizedHandleIsStableDerivedAndNeverTheRawUuid() {
        UUID buyer = UUID.fromString("6f9619ff-8b86-4011-b42d-00c04fc964ff");
        String handle = ReviewService.handleFor(buyer);
        assertEquals(handle, ReviewService.handleFor(buyer)); // stable
        assertTrue(handle.matches("Buyer-[0-9a-f]{4}"));
        assertTrue(!handle.contains(buyer.toString().substring(0, 4))
                || handle.length() == "Buyer-".length() + 4); // 4 hex chars, not the uuid
    }
}
