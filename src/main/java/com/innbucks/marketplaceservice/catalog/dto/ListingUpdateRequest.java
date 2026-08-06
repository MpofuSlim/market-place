package com.innbucks.marketplaceservice.catalog.dto;

import com.innbucks.marketplaceservice.catalog.ItemCondition;
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

        @Schema(description = "Curated taxonomy code (GET /marketplace/categories lists valid values). "
                + "Omitted/blank defaults to 'other'; an unknown code is refused with 400 unknown_category.",
                example = "tv-audio", maxLength = 40, nullable = true)
        @Size(max = 40)
        String categoryCode,

        @Schema(description = "Item condition. Omitted defaults to NEW (full replace — send the "
                + "current value to keep it).",
                example = "USED_GOOD", nullable = true)
        ItemCondition condition,

        @Schema(description = "Seller's city (optional; HTML is stripped server-side).",
                example = "Harare", maxLength = 80, nullable = true)
        @Size(max = 80)
        String city,

        @Schema(description = "Neighbourhood/area within the city (optional; HTML is stripped "
                + "server-side).",
                example = "Avondale", maxLength = 120, nullable = true)
        @Size(max = 120)
        String area,

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
