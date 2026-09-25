package com.innbucks.marketplaceservice.cart.dto;

import com.innbucks.marketplaceservice.checkout.dto.PricedLineResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The buyer's whole cart, priced live.
 *
 * <p>Every mutation returns this same shape, so the app never has to re-read
 * the cart after changing it — and the totals it shows can never be a stale
 * client-side sum.
 */
@Schema(description = "A buyer's cart, priced against the live catalogue")
public record CartResponse(

        @Schema(description = "Lines, most recently added first")
        List<PricedLineResponse> items,

        @Schema(description = "Distinct lines in the cart - one listing in two sizes is two lines",
                example = "2")
        int lineCount,

        @Schema(description = "Total units across all lines — the number on the cart badge",
                example = "3")
        int totalQuantity,

        @Schema(description = "Sum of the lines that can actually be bought, in minor units "
                + "(cents). A cart holding a sold-out item shows what is still buyable rather "
                + "than a figure the shopper can never be charged. Delivery is NOT included — it "
                + "is only known once a delivery method is chosen, at checkout.",
                example = "4798")
        long subtotalCents,

        @Schema(description = "ISO-4217 cell currency", example = "USD")
        String currency,

        @Schema(description = "True when an order built from this cart would be accepted: at least "
                + "one line, and no line carrying an `issue`. The app gates its Checkout button on "
                + "this rather than re-deriving it.", example = "true")
        boolean checkoutReady) {
}
