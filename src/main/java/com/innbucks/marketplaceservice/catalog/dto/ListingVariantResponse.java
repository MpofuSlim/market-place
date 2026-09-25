package com.innbucks.marketplaceservice.catalog.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.catalog.variant.ListingVariant;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * One option of a listing as every surface shows it (V19): the product page's
 * picker, the seller's editor, and a cart or quote line that named it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One option of a listing (a size, a colour, or both)")
public record ListingVariantResponse(

        @Schema(description = "Option id - send it as `variantId` on the cart, the quote and the order",
                example = "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352")
        UUID id,

        @Schema(description = "One value per option axis, in the listing's `options` order",
                example = "[\"M\", \"Black\"]")
        List<String> values,

        @Schema(description = "Display label, ready to print", example = "M - Black")
        String label,

        @Schema(description = "What this option costs, in MINOR units (cents)", example = "1999")
        long priceCents,

        @Schema(description = "Present only when this option has a price of its own (a surcharge "
                + "above the listing's `priceCents`). Absent means it sells at the listing price - "
                + "an editor should then send no priceCents for it.",
                example = "2299", nullable = true)
        Long priceOverrideCents,

        @Schema(description = "Units of this option on sale right now", example = "4")
        int stockQty
) {

    public static ListingVariantResponse from(ListingVariant variant, long listingPriceCents) {
        return new ListingVariantResponse(variant.getId(), variant.values(), variant.label(),
                variant.effectivePriceCents(listingPriceCents), variant.getPriceCents(),
                variant.getStockQty());
    }
}
