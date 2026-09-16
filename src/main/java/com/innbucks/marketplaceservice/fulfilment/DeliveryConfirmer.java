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
 */
@Schema(description = "Who marked the parcel delivered")
public enum DeliveryConfirmer {
    BUYER,
    MERCHANT
}
