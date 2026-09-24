package com.innbucks.marketplaceservice.settlement;

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
 * One parcel's share of the money (V10 {@code merchant_settlement}).
 *
 * <p>Keyed by PARCEL: V9's {@code order_fulfilment} row already IS "one
 * seller's share of one order", it is the thing whose delivery earns the
 * money, and the unique index on {@code fulfilment_id} makes opening
 * settlements exactly as replay-safe as opening parcels.
 *
 * <p>{@code gross/commission/net} are all STORED with the arithmetic
 * CHECK-enforced — a ledger a reader must re-derive is not a ledger.
 * Commission is 0 until the platform decides otherwise
 * ({@code marketplace.settlement.commission-percent}); the columns exist so
 * that decision is a config change, not a migration.
 */
@Entity
@Table(name = "merchant_settlement")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantSettlement {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "fulfilment_id", nullable = false)
    private UUID fulfilmentId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SettlementStatus status;

    /** Sum of this merchant's line totals on the order, in minor units. */
    /** Goods PLUS this seller's delivery fee (V14): the whole of what the
     *  buyer paid for this parcel, and so what a refund of it returns. */
    @Column(name = "gross_cents", nullable = false)
    private long grossCents;

    /** The delivery part of {@code grossCents} (V14) — the seller delivered,
     *  so it is theirs, held and released with the goods. Commission is
     *  charged on the goods only. */
    @Column(name = "delivery_fee_cents", nullable = false, updatable = false)
    private long deliveryFeeCents;

    @Column(name = "commission_cents", nullable = false)
    private long commissionCents;

    /** What the seller is actually owed: {@code gross - commission}. */
    @Column(name = "net_cents", nullable = false)
    private long netCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** When a seller-closed parcel's grace lapses and the sweeper may
     *  release. Null on the buyer-confirmed instant path. */
    @Column(name = "releasable_at")
    private Instant releasableAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "paid_out_at")
    private Instant paidOutAt;

    /** The operator's payout run reference — what "where is my money?" is
     *  answered with. */
    @Column(name = "payout_reference", length = 64)
    private String payoutReference;

    /** When the parcel behind this money was declared unfulfillable and the
     *  refund was queued (V12). Null on every other path. */
    @Column(name = "refund_due_at")
    private Instant refundDueAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    @Column(name = "refund_reference", length = 64)
    private String refundReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Wrapper for the manually-assigned-id new-row detection, as everywhere. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
