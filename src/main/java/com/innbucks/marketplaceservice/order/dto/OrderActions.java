package com.innbucks.marketplaceservice.order.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the buyer may do with the order as a whole — per-parcel actions live on
 * each parcel. Computed by the server from the same rule its endpoint enforces
 * (the order state machine).
 */
@Schema(description = "What the buyer may do with the order as a whole, decided by the server. "
        + "Per-parcel actions are on each fulfilment's `actions`.")
public record OrderActions(

        @Schema(description = "Cancel the whole order - POST /marketplace/orders/{id}/cancel. "
                + "Only while it is awaiting payment; once paid, cancel per parcel instead.",
                example = "false")
        boolean canCancel) {
}
