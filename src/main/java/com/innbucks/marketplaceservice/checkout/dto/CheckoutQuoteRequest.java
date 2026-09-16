package com.innbucks.marketplaceservice.checkout.dto;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * What to quote. Source the basket EITHER from the buyer's cart
 * ({@code fromCart: true}) OR from explicit {@code items} — never both, so a
 * quote is never ambiguous about what it priced.
 *
 * <p>Deliberately the same shape as {@code CreateOrderRequest}: the quote is a
 * dry run of the order, and a client should be able to send the identical body
 * to both. A quote that took a different shape would be a second thing to keep
 * in step.
 */
@Schema(description = "A checkout quote request — prices a basket and totals it, without "
        + "reserving any stock")
public record CheckoutQuoteRequest(

        @Schema(description = "Price the buyer's whole cart. Mutually exclusive with `items`.",
                example = "true", nullable = true)
        Boolean fromCart,

        @Schema(description = "Explicit lines — for Buy Now, which bypasses the cart. Mutually "
                + "exclusive with `fromCart`.", nullable = true)
        @Valid
        List<Item> items,

        @Schema(description = "How the buyer wants the goods. Defaults to COLLECTION — the same "
                + "thing an order with no delivery instruction has always meant here, so an "
                + "un-updated client is never 400ed for an address it does not know to send. "
                + "Delivery is an explicit choice: it costs money and needs somewhere to go.",
                example = "DELIVERY", nullable = true)
        DeliveryMethod deliveryMethod,

        @Schema(description = "Which saved address to deliver to. Ignored for COLLECTION. Omitted "
                + "on a DELIVERY quote means the buyer's default address; a buyer with no saved "
                + "address gets 400 `delivery_address_required` rather than a quote missing a "
                + "destination.",
                example = "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45", nullable = true)
        UUID deliveryAddressId) {

    @Schema(description = "One line to quote")
    public record Item(

            @Schema(example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93")
            @NotNull
            UUID listingId,

            @Schema(example = "2")
            @NotNull
            @Min(1)
            Integer quantity) {
    }

    public boolean sourcedFromCart() {
        return Boolean.TRUE.equals(fromCart);
    }
}
