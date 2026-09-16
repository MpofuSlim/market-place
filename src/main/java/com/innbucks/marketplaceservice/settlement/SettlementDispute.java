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
 * A buyer's dispute over one parcel (V10 {@code settlement_dispute}).
 *
 * <p><b>One dispute per parcel, EVER</b> — {@code uq_dispute_fulfilment} is
 * the backstop. A buyer who could re-dispute after an operator released could
 * freeze a seller's money in a loop; a second complaint after resolution is a
 * support matter, not a ledger state.
 *
 * <p>Denormalises {@code order_id}/{@code merchant_id}/{@code buyer_uuid}
 * from the rows it references so the operator queue renders without joins and
 * the owner-scoping check reads one row.
 */
@Entity
@Table(name = "settlement_dispute")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SettlementDispute {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "settlement_id", nullable = false)
    private UUID settlementId;

    @Column(name = "fulfilment_id", nullable = false)
    private UUID fulfilmentId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "buyer_uuid", nullable = false)
    private UUID buyerUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private DisputeReason reason;

    /** The buyer's own words — jsoup-sanitized before storage, shown to the
     *  operator. Never audited (audit carries the reason only). */
    @Column(name = "detail", length = 1000)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DisputeStatus status;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
