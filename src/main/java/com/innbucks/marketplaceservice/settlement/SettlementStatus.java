package com.innbucks.marketplaceservice.settlement;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where one parcel's money is (V10 {@code merchant_settlement.status}).
 *
 * <p>This is the escrow: the platform has already COLLECTED the buyer's money
 * (payment-service, at order PAID); this lifecycle decides when the seller may
 * be PAID it. "The seller only gets paid when you get your goods" is exactly
 * the HELD→RELEASABLE edge.
 */
@Schema(description = "Where one parcel's money is in the escrow lifecycle")
public enum SettlementStatus {

    /** Collected from the buyer, not yet earned by the seller — the parcel is
     *  still on its way (or a seller-closed delivery is inside its grace
     *  window, the buyer's chance to object). */
    HELD,

    /** Cleared for payout: the buyer confirmed receipt, the grace window
     *  lapsed unchallenged, or an operator resolved a dispute the seller's
     *  way. Waiting only on the operator's payout run. */
    RELEASABLE,

    /** Frozen by the buyer's dispute until an operator decides. */
    DISPUTED,

    /**
     * Owed back to the buyer, not yet sent (V12) — the mirror of RELEASABLE,
     * and it exists for the same reason: this service moves no money, so the
     * ledger needs a word for "decided, not yet transferred".
     *
     * <p>Reached when a seller declares a parcel UNFULFILLED. Collapsing it
     * into REFUNDED would record a transfer nobody made, and REFUNDED carries
     * a {@code refund_reference} precisely because it means the money left.
     */
    REFUND_DUE,

    /** The operator paid the seller ({@code payout_reference} says how).
     *  Terminal — money that has left cannot be un-sent by this ledger. */
    PAID_OUT,

    /** The operator refunded the buyer ({@code refund_reference}). Terminal —
     *  reached from a resolved dispute, or from REFUND_DUE once the operator
     *  has actually made the transfer. */
    REFUNDED
}
