package com.innbucks.marketplaceservice.catalog;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

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

    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "stock_qty", nullable = false)
    private int stockQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ListingStatus status;

    // Listing image bytes. Declared as BYTEA — Postgres has no length cap that
    // would truncate real-world images, unlike the default Hibernate varbinary
    // mapping. Lazy-loaded so list endpoints don't pull bytes into memory;
    // clients fetch the bytes via GET /marketplace/catalog/{id}/image using
    // the imageUrl on the response. (Event-service banner design.)
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "image_bytes", columnDefinition = "BYTEA")
    @ToString.Exclude
    private byte[] imageBytes;

    @Column(name = "image_content_type", length = 64)
    private String imageContentType;

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

    /**
     * Image presence WITHOUT touching the lazy {@link #imageBytes}: the
     * content type is only ever written alongside the bytes (and cleared with
     * them), so its presence is the marker. Used by
     * {@code ListingResponse.from} to derive {@code imageUrl} on list
     * endpoints without loading megabytes per row.
     */
    public boolean hasImage() {
        return imageContentType != null && !imageContentType.isBlank();
    }
}
