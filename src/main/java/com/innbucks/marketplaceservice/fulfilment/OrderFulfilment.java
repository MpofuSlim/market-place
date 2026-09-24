package com.innbucks.marketplaceservice.fulfilment;

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
 * One seller's parcel of one order (V9 {@code order_fulfilment} table).
 *
 * <p>Per MERCHANT, not per order, because a cart can legitimately span sellers
 * and each ships their own goods on their own clock. An order-level fulfilment
 * state would either block the fast seller behind the slow one or claim the
 * whole order shipped when half of it had. The buyer's order view rolls the
 * parcels up to the LEAST advanced one — the only summary that is never an
 * overstatement.
 *
 * <p>Rows are opened when the order is PAID, in the confirming transaction, so
 * an unpaid order has none: there is nothing to pack until the money has moved.
 * The unique index on (order_id, merchant_id) makes opening them safely
 * re-runnable — a replayed payment confirm cannot double them.
 */
@Entity
@Table(name = "order_fulfilment")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderFulfilment {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    /** The SNAPSHOT merchant from the order's lines, not a live listing lookup:
     *  the seller who owes these goods is the one who was selling at order time. */
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private FulfilmentStatus status;

    /** Courier + waybill, or "ready at the Avondale counter". Sanitized free
     *  text, shown to the buyer. */
    @Column(name = "dispatch_note", length = 255)
    private String dispatchNote;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivered_by", length = 16)
    private DeliveryConfirmer deliveredBy;

    // ---- Unfulfillable (V12) -------------------------------------------

    /** When the seller declared they could not supply this parcel. */
    @Column(name = "unfulfilled_at")
    private Instant unfulfilledAt;

    /** The seller's own words, shown to the buyer — sanitized free text. The
     *  buyer is losing something they paid for; "out of stock" is the
     *  difference between an explanation and a silent disappearance. */
    @Column(name = "unfulfilled_reason", length = 255)
    private String unfulfilledReason;

    /** Per-parcel double-return guard, sibling of {@code market_order
     *  .stock_released} one level up — that flag is per ORDER and owned by
     *  cancel/expiry, so a parcel released on its own needs its own. */
    @Column(name = "stock_returned", nullable = false)
    private boolean stockReturned;

    /** Who ended it (V16) — SELLER for a decline or a no-show, BUYER for a
     *  cancellation before dispatch. Set with the UNFULFILLED move, null
     *  otherwise (CHECK-enforced). */
    @Enumerated(EnumType.STRING)
    @Column(name = "unfulfilled_by", length = 16)
    private UnfulfilledBy unfulfilledBy;

    /** When the seller was told this collection has waited too long (V16).
     *  Claimed by the sweep's own bulk UPDATE before it sends, never through
     *  the entity — so, like the location columns, it is read-only here. */
    @Column(name = "collection_overdue_alerted_at", insertable = false, updatable = false)
    private Instant collectionOverdueAlertedAt;

    // ---- Collection handover code (V11) --------------------------------
    // Set only on a COLLECTION parcel, and only once the buyer has asked for a
    // code. The plaintext is never stored — see CollectCodes.

    /** SHA-256 hex of the live code. Null until one is minted; REPLACED, never
     *  appended to, when the buyer mints a fresh one (the old code dies with
     *  the hash it hashed to). */
    @Column(name = "collect_code_hash", length = 64)
    private String collectCodeHash;

    @Column(name = "collect_code_issued_at")
    private Instant collectCodeIssuedAt;

    /** When a seller redeemed it — the handover itself. */
    @Column(name = "collect_code_redeemed_at")
    private Instant collectCodeRedeemedAt;

    /** Wrong codes tried against this parcel. Never written through this field
     *  (see CollectCodeAttempts): a read-modify-write here would lose
     *  increments and hand an attacker a budget that never runs down. */
    @Column(name = "collect_code_attempts", nullable = false)
    private int collectCodeAttempts;

    // ---- Delivery + tracking (V14) --------------------------------------

    /** This seller's delivery fee on the order, copied from the order's
     *  per-seller snapshot when the parcel opens. 0 on a COLLECTION order. */
    @Column(name = "delivery_fee_cents", nullable = false, updatable = false)
    private long deliveryFeeCents;

    /** {@code TRK-XXXXXXXXXX}: a lookup key for the seller, an operator and
     *  the buyer — never a credential. Set once, at opening. */
    @Column(name = "tracking_code", nullable = false, updatable = false, length = 20)
    private String trackingCode;

    // The courier's LAST known position. Read-only through this entity: only
    // OrderFulfilmentRepository.recordLocation writes them, so a seller's
    // save of this row can never put a stale position back.

    @Column(name = "last_latitude", precision = 9, scale = 6, insertable = false, updatable = false)
    private java.math.BigDecimal lastLatitude;

    @Column(name = "last_longitude", precision = 9, scale = 6, insertable = false, updatable = false)
    private java.math.BigDecimal lastLongitude;

    @Column(name = "last_accuracy_m", insertable = false, updatable = false)
    private Integer lastAccuracyMeters;

    @Column(name = "last_location_at", insertable = false, updatable = false)
    private Instant lastLocationAt;

    /** Who posted it (user uuid) — for an operator looking at a dispute. */
    @Column(name = "last_location_by", length = 64, insertable = false, updatable = false)
    private String lastLocationBy;

    /** The last message a seller action sent the buyer (V15) — a
     *  {@code BuyerNoticeKind} name. Read-only here: written only by
     *  {@code OrderFulfilmentRepository.recordBuyerNotice}, never by a save. */
    @Column(name = "buyer_notice_kind", length = 24, insertable = false, updatable = false)
    private String buyerNoticeKind;

    /** Whether it went out — a {@code BuyerNoticeOutcome} name. */
    @Column(name = "buyer_notice_outcome", length = 16, insertable = false, updatable = false)
    private String buyerNoticeOutcome;

    @Column(name = "buyer_notice_at", insertable = false, updatable = false)
    private Instant buyerNoticeAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Wrapper {@code Long} for the same reason {@code MarketOrder}'s is: with a
     *  manually assigned id, Spring Data decides new-vs-existing by this field
     *  being null. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
