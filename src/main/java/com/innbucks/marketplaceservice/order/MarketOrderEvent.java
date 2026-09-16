package com.innbucks.marketplaceservice.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only order transition journal (V1 {@code market_order_event} table),
 * written IN THE SAME TRANSACTION as the status change by
 * {@link OrderTransitionService} — the payment-service {@code payment_event}
 * pattern, so the order and its history cannot diverge. {@code fromStatus} is
 * null for the creation row; {@code from == to} rows are annotations without
 * a status change (e.g. an S2S expiry extension).
 *
 * <p>{@code kind} (V9) says WHICH lifecycle a row belongs to — the payment
 * states or the fulfilment ones. Both land here rather than in two tables
 * because "what happened to this order" is one question, and answering it from
 * two append-only tables merged by timestamp is how the answer starts
 * disagreeing with itself. The two vocabularies do not overlap today, so a
 * reader could infer it — but inferring a column's meaning from its values is
 * how a journal becomes unreadable the first time a status name is reused.
 */
@Entity
@Table(name = "market_order_event")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketOrderEvent {

    /** Payment lifecycle (PENDING_PAYMENT/PAID/CANCELLED/EXPIRED) — the default
     *  for every pre-V9 row. */
    public static final String KIND_PAYMENT = "PAYMENT";

    /** Fulfilment lifecycle (PREPARING/DISPATCHED/DELIVERED). */
    public static final String KIND_FULFILMENT = "FULFILMENT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    /** {@link #KIND_PAYMENT} or {@link #KIND_FULFILMENT}. Defaulted in the
     *  builder so a caller that predates the column still writes a valid row. */
    @Builder.Default
    @Column(name = "kind", nullable = false, length = 16)
    private String kind = KIND_PAYMENT;

    @Column(name = "from_status", length = 24)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 24)
    private String toStatus;

    @Column(name = "detail", length = 255)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
