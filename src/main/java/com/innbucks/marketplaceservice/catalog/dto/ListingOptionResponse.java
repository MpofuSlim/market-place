package com.innbucks.marketplaceservice.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One option axis of a listing (V19) with the values its options use, in the
 * order the seller listed them — what a picker renders as a row of chips, so
 * the app derives nothing from the variants itself.
 */
@Schema(description = "One option axis and its values")
public record ListingOptionResponse(

        @Schema(description = "Axis name", example = "Size")
        String name,

        @Schema(description = "Distinct values in the seller's order", example = "[\"M\", \"L\", \"XL\"]")
        List<String> values
) {
}
