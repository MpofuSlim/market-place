package com.innbucks.marketplaceservice.catalog.variant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One option of a listing (V19 {@code listing_variant}): "M - Black", with its
 * own stock and, optionally, a price above the listing's.
 *
 * <p>{@code stockQty} is the truth for this option and is {@code updatable =
 * false}: inserted with the row, then moved ONLY by the native statements in
 * {@link ListingVariantRepository}, each run under the parent listing's row
 * lock and followed by the listing total's recompute (see
 * {@code ListingStock}). An entity save can therefore never undo a
 * reservation made in between.
 *
 * <p>{@code priceCents} is an optional SURCHARGE override: null means the
 * option sells at the listing price. It is never below the listing price
 * (the floor rule, enforced by {@link VariantSetResolver}), so the listing's
 * price is always the "from" price.
 */
@Entity
@Table(name = "listing_variant")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ListingVariant {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "listing_id", nullable = false, updatable = false)
    private UUID listingId;

    @Column(name = "option1_value", nullable = false, length = 40)
    private String option1Value;

    @Column(name = "option2_value", length = 40)
    private String option2Value;

    /** {@link VariantLabels#key}: the ONE definition of "the same option". */
    @Column(name = "option_key", nullable = false, length = 200)
    private String optionKey;

    /** Null = the listing price. */
    @Column(name = "price_cents")
    private Long priceCents;

    @Column(name = "stock_qty", nullable = false, updatable = false)
    private int stockQty;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Wrapper, so a save of a brand-new row is an INSERT without a SELECT. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Sets both values and the key they imply, so the two cannot disagree. */
    public void setValues(String value1, String value2) {
        this.option1Value = value1;
        this.option2Value = value2;
        this.optionKey = VariantLabels.key(value1, value2);
    }

    /** "M" or "M - Black" — what every surface prints. */
    public String label() {
        return VariantLabels.label(option1Value, option2Value);
    }

    /** The option values in axis order. */
    public List<String> values() {
        List<String> values = new ArrayList<>(2);
        values.add(option1Value);
        if (option2Value != null) {
            values.add(option2Value);
        }
        return List.copyOf(values);
    }

    /** What this option costs: its override, else the listing price. */
    public long effectivePriceCents(long listingPriceCents) {
        return priceCents != null ? priceCents : listingPriceCents;
    }
}
