package com.innbucks.marketplaceservice.review;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.review.dto.MerchantRatingResponse;
import com.innbucks.marketplaceservice.review.dto.PublicReviewResponse;
import com.innbucks.marketplaceservice.review.dto.ReviewPageResponse;
import com.innbucks.marketplaceservice.review.dto.ReviewRequest;
import com.innbucks.marketplaceservice.review.dto.ReviewResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verified-purchase reviews (V5). THE invariant: a review exists only when its
 * author has a PAID order containing the listing — enforced on create by
 * querying the order tables, and recorded as {@code order_id} provenance on
 * the row. One review per buyer per listing (existsBy check + unique-index
 * backstop). Any listing STATUS is reviewable — a delisted product was still
 * bought.
 *
 * <p><b>Aggregates discipline:</b> the listing's {@code rating_sum}/
 * {@code rating_count} are adjusted via ONE atomic bulk UPDATE
 * ({@link ListingRepository#adjustRatingAggregates}) inside the same
 * transaction as every review INSERT/UPDATE/DELETE — never read-modify-write
 * through the {@code Listing} entity (the stock_qty discipline), so two
 * concurrent reviewers can never lose each other's increments.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    /** Same hard pagination cap as the public catalog. */
    static final int MAX_PAGE_SIZE = 50;

    /** Constant public display name — every review on the catalog surface is
     *  purchase-verified by construction. */
    static final String REVIEWER_NAME = "Verified buyer";

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final ListingReviewRepository reviewRepository;
    private final ListingRepository listingRepository;
    private final MarketOrderRepository orderRepository;
    private final AuditService auditService;
    private final MarketplaceMetrics metrics;

    // ------------------------------------------------------------------
    // Buyer writes
    // ------------------------------------------------------------------

    @Transactional
    public ReviewResponse create(AuthenticatedUser caller, UUID listingId, ReviewRequest request) {
        Listing listing = requiredListing(listingId);
        UUID buyerUuid = UUID.fromString(caller.uuid());
        int rating = requiredRating(request.rating());

        // THE gate: a PAID order of this buyer must contain the listing. The
        // oldest qualifying order becomes the review's provenance.
        List<UUID> paidOrders = orderRepository.findPaidOrderIdsContainingListing(
                buyerUuid, listingId, PageRequest.of(0, 1));
        if (paidOrders.isEmpty()) {
            metrics.reviewOutcome("rejected_unverified");
            throw ApiException.forbidden("review_requires_purchase",
                    "Only buyers with a paid order containing this listing may review it");
        }
        if (reviewRepository.existsByListingIdAndBuyerUuid(listingId, buyerUuid)) {
            metrics.reviewOutcome("duplicate");
            throw ApiException.conflict("review_already_exists",
                    "You have already reviewed this listing");
        }

        Instant now = Instant.now();
        ListingReview review = ListingReview.builder()
                .id(UUID.randomUUID())
                .listingId(listingId)
                .merchantId(listing.getMerchantId())
                .buyerUuid(buyerUuid)
                .orderId(paidOrders.getFirst())
                .rating(rating)
                .comment(sanitizedOrNull(request.comment()))
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            // Flushed HERE (not at commit) so a concurrent same-buyer create
            // losing the (buyer_uuid, listing_id) unique-index race surfaces as
            // the same 409 as the existsBy check — never a 500 — and never
            // reaches the aggregate update below.
            reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException ex) {
            metrics.reviewOutcome("duplicate");
            throw ApiException.conflict("review_already_exists",
                    "You have already reviewed this listing");
        }
        listingRepository.adjustRatingAggregates(listingId, rating, 1);
        auditService.record(AuditEventType.REVIEW_CREATED, caller.uuid(), review.getId().toString(),
                Map.of("listingId", listingId.toString(),
                        "merchantId", listing.getMerchantId().toString(),
                        "orderId", review.getOrderId().toString(),
                        "rating", rating));
        metrics.reviewOutcome("created");
        return ReviewResponse.from(review);
    }

    /** PUT /reviews/mine — the caller edits their own review; the aggregate
     *  absorbs the rating DELTA atomically (count unchanged). */
    @Transactional
    public ReviewResponse updateMine(AuthenticatedUser caller, UUID listingId, ReviewRequest request) {
        int rating = requiredRating(request.rating());
        ListingReview review = reviewRepository
                .findByListingIdAndBuyerUuid(listingId, UUID.fromString(caller.uuid()))
                .orElseThrow(() -> ApiException.notFound("review_not_found",
                        "You have no review of this listing"));
        int delta = rating - review.getRating();
        review.setRating(rating);
        review.setComment(sanitizedOrNull(request.comment()));
        review.setUpdatedAt(Instant.now());
        reviewRepository.save(review);
        if (delta != 0) {
            listingRepository.adjustRatingAggregates(listingId, delta, 0);
        }
        auditService.record(AuditEventType.REVIEW_UPDATED, caller.uuid(), review.getId().toString(),
                Map.of("listingId", listingId.toString(),
                        "rating", rating,
                        "ratingDelta", delta));
        return ReviewResponse.from(review);
    }

    /**
     * DELETE /reviews/{reviewId} — the AUTHOR removes their own review, or
     * SUPER_ADMIN removes anyone's (moderation). A non-author non-admin gets
     * 403 {@code review_not_owned} (the review id is already public on the
     * catalog read, so a specific 403 leaks nothing a 404 would hide).
     */
    @Transactional
    public void delete(AuthenticatedUser caller, UUID listingId, UUID reviewId) {
        ListingReview review = reviewRepository.findByIdAndListingId(reviewId, listingId)
                .orElseThrow(() -> ApiException.notFound("review_not_found",
                        "No such review for this listing"));
        boolean adminRemoval = caller.isSuperAdmin();
        if (!adminRemoval && !review.getBuyerUuid().toString().equals(caller.uuid())) {
            throw ApiException.forbidden("review_not_owned",
                    "Only the review's author or an administrator may delete it");
        }
        reviewRepository.delete(review);
        listingRepository.adjustRatingAggregates(listingId, -review.getRating(), -1);
        auditService.record(AuditEventType.REVIEW_DELETED, caller.uuid(), reviewId.toString(),
                Map.of("listingId", listingId.toString(),
                        "merchantId", review.getMerchantId().toString(),
                        "authorUuid", review.getBuyerUuid().toString(),
                        "rating", review.getRating(),
                        "adminRemoval", adminRemoval));
    }

    // ------------------------------------------------------------------
    // Public reads
    // ------------------------------------------------------------------

    /**
     * A listing's reviews, newest first, reviewer anonymized. Served for ANY
     * listing status (the image-endpoint stance: a delisted product's reviews
     * remain readable, UUIDs are unguessable); 404 only when the listing id is
     * unknown entirely.
     */
    @Transactional(readOnly = true)
    public ReviewPageResponse listForListing(UUID listingId, int page, int size) {
        requiredListing(listingId);
        PageRequest pageable = PageRequest.of(Math.max(page, 0),
                Math.clamp(size, 1, MAX_PAGE_SIZE), NEWEST_FIRST);
        return ReviewPageResponse.from(
                reviewRepository.findByListingId(listingId, pageable).map(ReviewService::toPublic));
    }

    /** Merchant aggregate over the review table (never 404s — an unknown or
     *  review-less merchant is simply {ratingAvg: null, reviewCount: 0}). */
    @Transactional(readOnly = true)
    public MerchantRatingResponse merchantRating(UUID merchantId) {
        long count = reviewRepository.countByMerchantId(merchantId);
        if (count == 0) {
            return new MerchantRatingResponse(merchantId, null, 0);
        }
        long sum = reviewRepository.sumRatingByMerchantId(merchantId);
        return new MerchantRatingResponse(merchantId,
                Math.round(sum * 10.0 / count) / 10.0, count);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static PublicReviewResponse toPublic(ListingReview review) {
        return new PublicReviewResponse(review.getId(), review.getRating(), review.getComment(),
                review.getCreatedAt(), REVIEWER_NAME, handleFor(review.getBuyerUuid()));
    }

    /**
     * Stable anonymized reviewer handle: {@code Buyer-} + the first 4 hex chars
     * of sha256(buyer uuid). One-way (no PII recoverable from 16 bits of a
     * SHA-256), but deterministic — a repeat reviewer carries the same handle
     * on every listing, so "same person reviewed twice" is visible to clients.
     */
    static String handleFor(UUID buyerUuid) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hex = HexFormat.of().formatHex(
                    digest.digest(buyerUuid.toString().getBytes(StandardCharsets.UTF_8)));
            return "Buyer-" + hex.substring(0, 4);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is a JCA standard algorithm — always present.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Listing requiredListing(UUID listingId) {
        return listingRepository.findById(listingId)
                .orElseThrow(() -> ApiException.notFound("listing_not_found", "Listing not found"));
    }

    /** Bean Validation already bounds the DTO; re-checked here so a future
     *  programmatic caller can't skip it (the ListingService.validateRanges
     *  stance). */
    private static int requiredRating(Integer rating) {
        if (rating == null || rating < 1 || rating > 5) {
            throw ApiException.badRequest("invalid_rating", "rating must be between 1 and 5");
        }
        return rating;
    }

    private static String sanitizedOrNull(String raw) {
        String sanitized = TextSanitizer.sanitize(raw);
        return (sanitized == null || sanitized.isBlank()) ? null : sanitized;
    }
}
