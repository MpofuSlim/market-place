package com.innbucks.marketplaceservice.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Merchant product listing (V1 {@code listing} table). Money is minor units
 * (cents, {@code long}); timestamps are UTC {@link Instant}s in TIMESTAMPTZ
 * columns.
 *
 * <p>{@code merchantId}/{@code shopId} are copied from the fleet JWT's claims
 * at create time — never from a request body. Stock movements bypass this
 * entity entirely (bulk {@code @Modifying} updates in {@link ListingRepository})
 * so reservation is a single atomic UPDATE, never a read-modify-write.
 */
@Entity
@Table(name = "listing")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Listing {

    /** Manually assigned (never DB-generated) so the id exists before the
     *  INSERT and can be referenced in the same transaction. */
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "shop_id")
    private UUID shopId;

    @Column(name = "title", nullable = false, length = 160)
    private String title;

    @Column(name = "description", length = 4000)
    private String description;

    /** FK onto the V4 curated {@code category} taxonomy — validated against
     *  the table on every write (400 {@code unknown_category}); defaults to
     *  {@code other}. Replaced the V1 free-text category column. */
    @Builder.Default
    @Column(name = "category_code", nullable = false, length = 40)
    private String categoryCode = "other";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "condition", nullable = false, length = 20)
    private ItemCondition condition = ItemCondition.NEW;

    /** Optional location, jsoup-sanitized like every other free-text field.
     *  The browse ?city= filter matches lower(city) exactly; geo/radius
     *  search is future work. */
    @Column(name = "city", length = 80)
    private String city;

    @Column(name = "area", length = 120)
    private String area;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "stock_qty", nullable = false)
    private int stockQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ListingStatus status;

    /** Denormalized review aggregates (V5): kept in lock-step with
     *  {@code listing_review} by ATOMIC bulk updates
     *  ({@link ListingRepository#adjustRatingAggregates}) in the same
     *  transaction as every review write — never read-modify-write through
     *  this entity (the stock_qty discipline). Catalog reads derive
     *  ratingAvg/reviewCount from these two columns with zero extra queries. */
    @Builder.Default
    @Column(name = "rating_sum", nullable = false)
    private long ratingSum = 0;

    @Builder.Default
    @Column(name = "rating_count", nullable = false)
    private int ratingCount = 0;

    // Listing images live in the V3 listing_image table and are deliberately
    // NOT mapped as a collection here: metadata reads ride ListingImageRepository's
    // bytes-free projection, so no list endpoint can accidentally initialize a
    // byte-laden collection. See ListingImage.

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Wrapper {@code Long}, not primitive, deliberately: with a manually
     * assigned {@code @Id}, Spring Data decides new-vs-existing by the
     * {@code @Version} field being null — a primitive would force an
     * is-it-in-the-DB SELECT (merge) on every {@code save()} of a new row.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
