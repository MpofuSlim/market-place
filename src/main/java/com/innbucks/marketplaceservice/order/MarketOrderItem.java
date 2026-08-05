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

    @Column(name = "title_snapshot", nullable = false, length = 160)
    private String titleSnapshot;

    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "line_total_cents", nullable = false)
    private long lineTotalCents;
}
