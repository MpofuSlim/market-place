package com.innbucks.marketplaceservice.settlement;

import io.swagger.v3.oas.annotations.media.Schema;

/** A dispute's own life: open, then closed the seller's way or the buyer's.
 *  Closed is terminal — see the one-dispute-per-parcel rule on
 *  {@link SettlementDispute}. */
@Schema(description = "Dispute lifecycle")
public enum DisputeStatus {
    OPEN,
    /** Operator sided with the seller: the settlement went back to RELEASABLE. */
    RELEASED,
    /** Operator sided with the buyer: the settlement was refunded. */
    REFUNDED
}
