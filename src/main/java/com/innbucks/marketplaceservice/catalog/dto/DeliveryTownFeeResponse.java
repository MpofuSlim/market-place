package com.innbucks.marketplaceservice.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A town this listing delivers to")
public record DeliveryTownFeeResponse(

        @Schema(example = "bulawayo")
        String townCode,

        @Schema(example = "Bulawayo")
        String townName,

        @Schema(description = "Delivery fee to this town, MINOR units", example = "800")
        long feeCents) {
}
