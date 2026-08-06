package com.innbucks.marketplaceservice.review;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ListingReviewRepository extends JpaRepository<ListingReview, UUID> {

    boolean existsByListingIdAndBuyerUuid(UUID listingId, UUID buyerUuid);

    /** The caller's own review of a listing (one per buyer per listing — the
     *  unique index guarantees at most one row). */
    Optional<ListingReview> findByListingIdAndBuyerUuid(UUID listingId, UUID buyerUuid);

    /** Scoped by (id, listingId) so a reviewId can never be addressed through
     *  another listing's URL — same pairing discipline as gallery images. */
    Optional<ListingReview> findByIdAndListingId(UUID id, UUID listingId);

    Page<ListingReview> findByListingId(UUID listingId, Pageable pageable);

    /** Merchant-level aggregate inputs, straight off the review table (the
     *  idx_review_merchant index) — the source of truth, immune to any drift
     *  in the per-listing denormalized columns. */
    long countByMerchantId(UUID merchantId);

    @Query("select coalesce(sum(r.rating), 0) from ListingReview r where r.merchantId = :merchantId")
    long sumRatingByMerchantId(@Param("merchantId") UUID merchantId);
}
