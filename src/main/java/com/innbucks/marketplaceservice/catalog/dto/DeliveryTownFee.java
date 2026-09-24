package com.innbucks.marketplaceservice.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One town a listing delivers to, and what the seller charges to get it there. */
@Schema(description = "A town this listing delivers to, with the delivery fee for that town")
public record DeliveryTownFee(

        @Schema(description = "A code from GET /marketplace/delivery-towns", example = "bulawayo")
        @NotBlank
        @Size(max = 40)
        String townCode,

        @Schema(description = "Delivery fee to this town in MINOR units (cents); 0 = free delivery",
                example = "800", minimum = "0", maximum = "10000000")
        @NotNull
        @Min(0)
        @Max(10_000_000)
        Long feeCents) {
}
