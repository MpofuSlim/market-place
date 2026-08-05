package com.innbucks.marketplaceservice.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create payload for a merchant listing. Deliberately carries NO currency (the
 * cell currency is applied server-side) and NO merchant/shop scope (taken from
 * the JWT claims) — a client can never price in a foreign currency or list
 * under another merchant.
 */
@Schema(description = "Create a merchant listing (starts as DRAFT)")
public record ListingCreateRequest(

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
                example = "2599", minimum = "1", maximum = "100000000")
        @NotNull
        @Min(1)
        @Max(100_000_000)
        Long priceCents,

        @Schema(description = "Units in stock available for reservation.",
                example = "120", minimum = "0", maximum = "1000000")
        @NotNull
        @Min(0)
        @Max(1_000_000)
        Integer stockQty
) {
}
