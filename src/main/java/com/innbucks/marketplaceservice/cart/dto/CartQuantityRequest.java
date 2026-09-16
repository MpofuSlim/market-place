package com.innbucks.marketplaceservice.cart.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Set-an-exact-quantity payload — the stepper control's write.
 *
 * <p>Deliberately a different endpoint from add-to-cart: "make it 3" and "add
 * 3 more" are different intentions, and collapsing them into one write is how
 * a retried request silently buys six.
 */
@Schema(description = "Set a cart line to an exact quantity")
public record CartQuantityRequest(

        @Schema(description = "The exact number of units the line should hold. Must be at least 1 — "
                + "removing a line is DELETE, so that zero can never be a silently-swallowed "
                + "delete on a retry.", example = "3")
        @NotNull
        @Min(1)
        Integer quantity) {
}
