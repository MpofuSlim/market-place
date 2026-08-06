package com.innbucks.marketplaceservice.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Verified-purchase review (V5 {@code listing_review} table). Written only by
 * a buyer with a PAID order containing the listing — {@code orderId} records
 * WHICH order qualified them. {@code merchantId} is denormalized from the
 * listing at write time so merchant-level rating reads never join through
 * {@code listing}.
 *
 * <p>The listing's {@code rating_sum}/{@code rating_count} aggregates are
 * maintained ATOMICALLY ({@link com.innbucks.marketplaceservice.catalog.ListingRepository#adjustRatingAggregates})
 * in the same transaction as every write to this table — never through the
 * {@code Listing} entity.
 *
 * <p>Manually-assigned id with no {@code @Version}: like
 * {@code MarketOrderItem}, {@code save()} therefore merges (one extra SELECT
 * on insert) — acceptable at review volume, and edits go through the loaded
 * entity anyway. The (buyer_uuid, listing_id) unique index is the DB backstop
 * under the service's existsBy duplicate check.
 */
@Entity
@Table(name = "listing_review")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ListingReview {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "buyer_uuid", nullable = false)
    private UUID buyerUuid;

    /** The PAID order that qualified this reviewer (verified-purchase provenance). */
    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    /** 1..5 — CHECK-constrained in the DB, re-validated in the service. */
    @Column(name = "rating", nullable = false)
    private int rating;

    /** Optional free text, jsoup-sanitized before storage (blank-after-strip
     *  stores null). */
    @Column(name = "comment", length = 1000)
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
