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

    // ------------------------------------------------------------------
    // Payout destination (V13) — where this seller's released money goes.
    //
    // Complete or absent, never half: chk_seller_payout_destination refuses a
    // method with no account behind it, which would otherwise read as
    // configured on every screen and fail only when a transfer is attempted.
    // ------------------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_method", length = 20)
    private PayoutMethod payoutMethod;

    /**
     * The name the destination account is held in — NOT {@link #displayName}.
     * The trading name is what shoppers see; this is what finance checks the
     * transfer against and what a bank rejects a payment for not matching.
     * They differ routinely: "Rudo Traders" paying into "R. Chikwanha".
     */
    @Column(name = "payout_account_name", length = 120)
    private String payoutAccountName;

    /** MOBILE_MONEY only. E.164, normalised through {@code Msisdns}. */
    @Column(name = "payout_msisdn", length = 20)
    private String payoutMsisdn;

    /** BANK only. */
    @Column(name = "payout_bank_name", length = 120)
    private String payoutBankName;

    /** BANK only. Free text — account-number formats vary per bank, and a
     *  format guess that refuses a valid account is worse than no check. */
    @Column(name = "payout_account_number", length = 40)
    private String payoutAccountNumber;

    /** When the destination last moved. Surfaced on the payout report because
     *  a destination that changed yesterday is the shape of a redirected
     *  payout, and finance reads that report before the money moves. */
    @Column(name = "payout_updated_at")
    private Instant payoutUpdatedAt;

    /** Who last moved it — the seller themselves, or an admin acting for them. */
    @Column(name = "payout_updated_by")
    private UUID payoutUpdatedBy;

    // ------------------------------------------------------------------
    // Collection (V20) — whether buyers may collect from this seller.
    //
    // Read-only on the entity: MarketplaceSellerRepository.setCollectionEnabled
    // is the only writer. The entity has no @Version, and decide() and the
    // payout-destination write save the WHOLE row, so a writable field here
    // would let either of them put back a value loaded before the seller
    // changed it.
    // ------------------------------------------------------------------

    /** False for a delivery-only seller. TRUE is what every seller was before
     *  V20, and it is the column default, so a row created without naming it
     *  still collects. {@code @Builder.Default} keeps that true for a
     *  builder-made seller too. */
    @Builder.Default
    @Column(name = "collection_enabled", nullable = false, updatable = false)
    private boolean collectionEnabled = true;

    /** When the seller (or an operator) last changed {@link #collectionEnabled};
     *  null while never changed. */
    @Column(name = "collection_updated_at", updatable = false)
    private Instant collectionUpdatedAt;

    /** Who last changed it. */
    @Column(name = "collection_updated_by", updatable = false)
    private UUID collectionUpdatedBy;

    /** Whether this seller can be paid without someone going and asking them. */
    public boolean hasPayoutDestination() {
        return payoutMethod != null;
    }

    /**
     * Whether buyers may collect from this seller. A seller with no record
     * collects: the record is created lazily, on the seller's first write, and
     * every seller collected before V20.
     */
    public static boolean collects(MarketplaceSeller sellerOrNull) {
        return sellerOrNull == null || sellerOrNull.isCollectionEnabled();
    }
}
