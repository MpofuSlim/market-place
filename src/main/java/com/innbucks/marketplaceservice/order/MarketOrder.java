package com.innbucks.marketplaceservice.order;

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

    @Column(name = "total_cents", nullable = false)
    private long totalCents;

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
