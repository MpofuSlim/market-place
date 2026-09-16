package com.innbucks.marketplaceservice.order;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;

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
 * Buyer order (V1 {@code market_order} table). Money is minor units (cents,
 * {@code long}); timestamps are UTC {@link Instant}s in TIMESTAMPTZ columns.
 *
 * <p>{@code orderRef} ({@code MKT-<12 hex>}) is the payments-facing opaque
 * reference — the platform payments service drives this order exclusively by
 * ref through {@code /marketplace/internal/orders/**}, mirroring the
 * booking-service contract. {@code stockReleased} is the double-release
 * guard: cancel and the expiry sweep restock exactly once even when they
 * overlap. {@code idempotencyKey} stores the namespaced claim-key hash — the
 * partial unique index on it is the DB backstop under the idempotency
 * claim-row.
 *
 * <p><b>Money (V9):</b> {@code totalCents = subtotalCents + deliveryFeeCents},
 * enforced by a CHECK constraint. {@code totalCents} remains the single number
 * the payments service collects — the split is stored because with a delivery
 * fee the total stopped being derivable from the lines.
 *
 * <p><b>Delivery (V9):</b> the destination is SNAPSHOT here, not referenced by
 * id — the {@code titleSnapshot} discipline one level up. A buyer who edits
 * "Home" after ordering must not silently redirect a parcel already on its way,
 * and deleting the book entry must not erase where a delivered order went.
 * {@code deliveryAddressId} is provenance only; nothing reads through it.
 */
@Entity
@Table(name = "market_order")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketOrder {

    /** Manually assigned (never DB-generated) so the id exists before the
     *  INSERT and item rows can reference it in the same transaction. */
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "order_ref", nullable = false, length = 40)
    private String orderRef;

    @Column(name = "buyer_uuid", nullable = false)
    private UUID buyerUuid;

    /** E.164, normalised by the order flow before storage. Never logged in full. */
    @Column(name = "buyer_msisdn", nullable = false, length = 20)
    private String buyerMsisdn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OrderStatus status;

    /** The payable total: {@code subtotalCents + deliveryFeeCents}. */
    @Column(name = "total_cents", nullable = false)
    private long totalCents;

    /** Sum of the order's lines, before delivery. */
    @Column(name = "subtotal_cents", nullable = false)
    private long subtotalCents;

    /** 0 for COLLECTION, and 0 on any cell that has not set a fee. */
    @Column(name = "delivery_fee_cents", nullable = false)
    private long deliveryFeeCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** The payments service's reference, set on confirm — the idempotency
     *  handle for confirm-payment replays. */
    @Column(name = "payment_ref", length = 64)
    private String paymentRef;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "stock_released", nullable = false)
    private boolean stockReleased;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    // ---- Delivery (V9) -------------------------------------------------
    // A DELIVERY order always has a destination — chk_order_delivery_destination
    // refuses one without, rather than trusting every future write path to
    // remember. A COLLECTION order has none of these set.

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", nullable = false, length = 16)
    private DeliveryMethod deliveryMethod;

    /** Which address-book entry was chosen — provenance only. Deliberately NOT
     *  a foreign key: the buyer may delete that entry, and this order's
     *  destination must survive it. */
    @Column(name = "delivery_address_id")
    private UUID deliveryAddressId;

    @Column(name = "delivery_recipient_name", length = 120)
    private String deliveryRecipientName;

    /** E.164. Never logged in full. */
    @Column(name = "delivery_recipient_msisdn", length = 20)
    private String deliveryRecipientMsisdn;

    @Column(name = "delivery_line1", length = 160)
    private String deliveryLine1;

    @Column(name = "delivery_line2", length = 160)
    private String deliveryLine2;

    @Column(name = "delivery_city", length = 80)
    private String deliveryCity;

    @Column(name = "delivery_area", length = 80)
    private String deliveryArea;

    @Column(name = "delivery_landmark", length = 160)
    private String deliveryLandmark;

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
