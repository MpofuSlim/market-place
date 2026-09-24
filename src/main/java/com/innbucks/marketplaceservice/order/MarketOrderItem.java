package com.innbucks.marketplaceservice.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * One order line (V1 {@code market_order_item} table). {@code titleSnapshot}
 * and {@code unitPriceCents} are copied from the listing row AT ORDER TIME so
 * a later merchant edit can never change what the buyer agreed to pay —
 * totals are always computed server-side from the listing, never taken from
 * the client.
 *
 * <p>{@code merchantId} (V9) is a snapshot for the same reason. It is what the
 * fulfilment queue and the merchant notifier group on, and joining back to the
 * live listing to find it is a lie waiting to happen: a listing can be archived
 * or transferred, and the seller who must pack and be paid is the one who was
 * selling AT ORDER TIME.
 *
 * <p>{@code variantId}/{@code variantLabel} (V19) are the same kind of
 * snapshot: what was bought, as it was named when it was bought.
 */
@Entity
@Table(name = "market_order_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketOrderItem {

    /** Manually assigned; the row is written once at order creation and never
     *  updated, so the merge-on-save SELECT is a non-issue. */
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    /** The selling merchant AS AT order time — never re-read from the listing. */
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "title_snapshot", nullable = false, length = 160)
    private String titleSnapshot;

    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "line_total_cents", nullable = false)
    private long lineTotalCents;

    /**
     * V19: the option bought, as provenance only — no FK, like
     * {@code listingId}. Null for a listing without variants (and every line
     * placed before V19). {@code listingId} stays the PARENT listing, which the
     * review gate, restock and reports key on.
     */
    @Column(name = "variant_id")
    private UUID variantId;

    /** V19: the option's label AS AT order time ("M - Black"), present exactly
     *  when {@code variantId} is ({@code chk_order_item_variant_snapshot}). A
     *  seller renaming or removing the option later changes nothing here. */
    @Column(name = "variant_label", length = 100)
    private String variantLabel;

    /**
     * The line's name for text surfaces (earnings rows, the statement, the
     * merchant notice): "Cotton Crew Tee (M - Black)", or exactly the title
     * snapshot when no option was bought — so every pre-V19 line renders
     * byte-identically.
     */
    public String displayTitle() {
        return variantLabel == null ? titleSnapshot : titleSnapshot + " (" + variantLabel + ")";
    }
}
