package com.innbucks.marketplaceservice.fulfilment;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Who closed a parcel.
 *
 * <p>Worth keeping distinct: a parcel the BUYER confirmed is evidence of
 * delivery in a way the seller's own say-so is not, and a dispute has to be
 * able to tell them apart. The seller can always close it themselves — a buyer
 * who simply never opens the app must not leave a parcel open forever — but the
 * record says which happened.
 *
 * <p>RECIPIENT (V11) is the third: somebody stood at the counter and produced
 * the collection code, which only the buyer and the person they sent were ever
 * given. It is the strongest of the three — the buyer's in-app tap says the
 * goods arrived, this says who took them — and it releases the seller's money
 * on the spot for exactly that reason.
 */
@Schema(description = "Who marked the parcel delivered")
public enum DeliveryConfirmer {
    BUYER,
    MERCHANT,
    RECIPIENT
}
