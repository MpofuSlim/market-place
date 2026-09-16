package com.innbucks.marketplaceservice.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A seller's OWN stats view: the public figures exactly as shoppers see them,
 * plus the operational counts that are the seller's business alone.
 *
 * <p>The public half is deliberately the same object the profile serves, not
 * a re-computation — a seller wondering "why does my profile say 2 days?" is
 * looking at the very number, from the very query, that the shopper sees.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A seller's own fulfilment stats — the public figures plus their live queue")
public record MerchantFulfilmentStatsResponse(

        @Schema(description = "What shoppers see on the public profile. Absent while there is no "
                + "completed parcel to compute from.", nullable = true)
        SellerFulfilmentStats publicStats,

        @Schema(description = "Parcels sitting in PREPARING — paid orders the seller has not "
                + "moved yet. The number to keep at zero.", example = "3")
        long awaitingDispatch,

        @Schema(description = "Parcels DISPATCHED but not yet delivered", example = "5")
        long inTransit,

        @Schema(description = "Parcels delivered, all time", example = "128")
        long completedOrders) {
}
