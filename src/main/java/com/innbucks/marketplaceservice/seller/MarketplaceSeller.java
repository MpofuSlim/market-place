package com.innbucks.marketplaceservice.seller;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One trust record per merchant (V8 {@code marketplace_seller}).
 *
 * <p>The PK IS the fleet merchant id — a merchant is the seller here, so a
 * surrogate key would only add a join and a way for two records to exist for
 * one merchant.
 */
@Entity
@Table(name = "marketplace_seller")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketplaceSeller {

    @Id
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private SellerStatus status = SellerStatus.PENDING;

    /** Trading name for the buyer-facing badge. Null until an admin sets one —
     *  this service has no source for a merchant's name (see V8's comment). */
    @Column(name = "display_name", length = 120)
    private String displayName;

    /** uuid of the SUPER_ADMIN who last decided. Null while never decided. */
    @Column(name = "decided_by")
    private UUID decidedBy;

    /** The admin's note. Required on reject/suspend — a seller told only "no"
     *  cannot fix anything. Optional on approve/reinstate. */
    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    /** When this seller first appeared — their first listing for a backfilled
     *  row, otherwise the moment the record was created. Drives the queue's
     *  oldest-first order and the badge's "selling since". */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;
}
