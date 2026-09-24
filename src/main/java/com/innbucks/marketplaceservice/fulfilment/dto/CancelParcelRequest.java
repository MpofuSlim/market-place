package com.innbucks.marketplaceservice.fulfilment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Why a buyer is calling off a parcel the seller has not sent yet. Optional. */
@Schema(description = "Cancel a paid parcel before the seller sends it - the money goes back to you")
public record CancelParcelRequest(

        @Schema(description = "Optional, in your own words - the seller is shown it.",
                example = "Ordered the wrong size", nullable = true)
        @Size(max = 255)
        String reason) {
}
