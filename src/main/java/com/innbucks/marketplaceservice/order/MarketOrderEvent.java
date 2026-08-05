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
 */
@Entity
@Table(name = "market_order_event")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketOrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "from_status", length = 24)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 24)
    private String toStatus;

    @Column(name = "detail", length = 255)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
