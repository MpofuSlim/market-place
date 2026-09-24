package com.innbucks.marketplaceservice.fulfilment;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Who ended a parcel that never arrived (V16).
 *
 * <p>The money behaves identically either way — it goes back to the buyer —
 * but the two are different facts to the people reading them: a seller must
 * not be told they "could not supply" an order the buyer called off, and a
 * buyer must not see the seller blamed for their own cancellation.
 */
@Schema(description = "Who ended a parcel that never arrived: SELLER (could not supply it, or "
        + "it was never collected) or BUYER (cancelled it before it was sent)")
public enum UnfulfilledBy {
    SELLER,
    BUYER
}
