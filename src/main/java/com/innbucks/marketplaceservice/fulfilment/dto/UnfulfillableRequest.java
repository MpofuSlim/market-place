package com.innbucks.marketplaceservice.fulfilment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why a seller cannot supply a parcel they were paid for. */
@Schema(description = "Decline a parcel you cannot supply - returns the stock and refunds the buyer")
public record UnfulfillableRequest(

        @Schema(description = "Why, in your own words - the buyer is shown this. Required: "
                + "somebody is losing something they paid for, and 'out of stock' is the "
                + "difference between an explanation and a silent disappearance.",
                example = "Out of stock - the last one was damaged in storage")
        @NotBlank
        @Size(max = 255)
        String reason) {
}
