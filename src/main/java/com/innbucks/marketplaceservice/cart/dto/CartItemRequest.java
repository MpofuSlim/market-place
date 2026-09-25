package com.innbucks.marketplaceservice.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Add-to-cart payload. */
@Schema(description = "Add units of a listing to the cart")
public record CartItemRequest(

        @Schema(example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93")
        @NotNull
        UUID listingId,

        @Schema(description = "Units to ADD to whatever is already in the cart for this listing "
                + "(defaults to 1). The result is capped at marketplace.order.max-quantity-per-item "
                + "rather than refused — a shopper tapping + past the cap wants the cap, not an error.",
                example = "1", nullable = true)
        @Min(1)
        Integer quantity,

        @Schema(description = "The option to add (V19) - REQUIRED when the listing has "
                + "`hasVariants: true` (400 variant_required otherwise), one of its `variants[].id` "
                + "(404 variant_not_found otherwise). Omit for a listing without options. Two "
                + "options of one listing are two cart lines.",
                example = "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352", nullable = true)
        UUID variantId) {

    /** A line on a listing without options — the pre-V19 shape. */
    public CartItemRequest(UUID listingId, Integer quantity) {
        this(listingId, quantity, null);
    }

    public int quantityOrOne() {
        return quantity == null ? 1 : quantity;
    }
}
