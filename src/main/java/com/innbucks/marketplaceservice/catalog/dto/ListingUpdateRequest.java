package com.innbucks.marketplaceservice.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Full-replace update payload (PUT). Same field rules as
 * {@link ListingCreateRequest}: no currency, no merchant/shop scope — those are
 * server-owned. Status is NOT updatable here; use the status endpoint.
 */
@Schema(description = "Replace a listing's content (status changes go through PATCH /status)")
public record ListingUpdateRequest(

        @Schema(description = "Display title. HTML is stripped server-side.",
                example = "Wireless Bluetooth Speaker", maxLength = 160)
        @NotBlank
        @Size(max = 160)
        String title,

        @Schema(description = "Free-text description. HTML is stripped server-side.",
                example = "Portable speaker with 12h battery life.", maxLength = 4000)
        @Size(max = 4000)
        String description,

        @Schema(description = "Category tag used for exact-match catalog filtering. HTML is stripped server-side.",
                example = "electronics", maxLength = 64)
        @Size(max = 64)
        String category,

        @Schema(description = "Unit price in MINOR units (cents). Currency is always the cell currency.",
                example = "2399", minimum = "1", maximum = "100000000")
        @NotNull
        @Min(1)
        @Max(100_000_000)
        Long priceCents,

        @Schema(description = "Units in stock available for reservation.",
                example = "150", minimum = "0", maximum = "1000000")
        @NotNull
        @Min(0)
        @Max(1_000_000)
        Integer stockQty
) {
}
