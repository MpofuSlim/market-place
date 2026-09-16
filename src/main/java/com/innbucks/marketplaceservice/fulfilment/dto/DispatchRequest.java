package com.innbucks.marketplaceservice.fulfilment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** What the seller says when they send a parcel — shown to the buyer verbatim
 *  (after sanitizing), so it is where tracking information actually reaches them. */
@Schema(description = "Mark a parcel dispatched, optionally telling the buyer how to track it")
public record DispatchRequest(

        @Schema(description = "Courier and waybill, or where to collect from. Optional, but it is "
                + "the only thing the buyer will see about how their goods are coming.",
                example = "Swift Couriers, waybill 88213", nullable = true)
        @Size(max = 255)
        String note) {
}
