package com.innbucks.marketplaceservice.checkout.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointChoice;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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

        @Schema(description = "Which saved address to deliver to. Used when some seller delivers "
                + "(ignored when every seller is collected). Omitted on such a quote means the "
                + "buyer's default address; a buyer with no saved address gets 400 "
                + "`delivery_address_required` rather than a quote missing a destination.",
                example = "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45", nullable = true)
        UUID deliveryAddressId,

        @Schema(description = "For the sellers who are COLLECTED: which of each seller's "
                + "collection points to collect from. A seller you do not name is collected from "
                + "their DEFAULT point; a seller with no points is arranged directly with them. An "
                + "entry for a seller who delivers is ignored.",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Valid
        @Size(max = 50)
        List<CollectionPointChoice> collectionPoints,

        @Schema(description = "V20: a method per SELLER, for a basket that is delivered from one "
                + "seller and collected from another. Each entry names a seller in the basket and "
                + "how their goods reach you; a seller you do not name takes `deliveryMethod`. "
                + "The address is needed exactly when some seller delivers. Only on a cell with "
                + "`perSellerDeliveryMethods: true` in `GET /marketplace/checkout/options` - "
                + "elsewhere a non-empty list is 422 `seller_delivery_methods_disabled`. A seller "
                + "named twice is 400 `duplicate_delivery_method_choice`; a method the cell does not "
                + "offer is 422 `delivery_method_unavailable` whose `data` names the seller. A null "
                + "entry is skipped, and an entry for a seller no longer in the basket is ignored. "
                + "Read each seller's method back from `sellers[].deliveryMethod` in the response.",
                nullable = true)
        // NON_NULL keeps a body that does not use this field byte-identical to
        // what it was before the field existed (the collectionPoints technique).
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Valid
        @Size(max = 50)
        List<SellerDeliveryChoice> sellerDeliveryMethods) {

    /** The shape before collection points existed. */
    public CheckoutQuoteRequest(Boolean fromCart, List<Item> items, DeliveryMethod deliveryMethod,
                                UUID deliveryAddressId) {
        this(fromCart, items, deliveryMethod, deliveryAddressId, null, null);
    }

    /** The shape before a method could be chosen per seller (V18-V20). */
    public CheckoutQuoteRequest(Boolean fromCart, List<Item> items, DeliveryMethod deliveryMethod,
                                UUID deliveryAddressId, List<CollectionPointChoice> collectionPoints) {
        this(fromCart, items, deliveryMethod, deliveryAddressId, collectionPoints, null);
    }

    @Schema(description = "One line to quote")
    public record Item(

            @Schema(example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93")
            @NotNull
            UUID listingId,

            @Schema(example = "2")
            @NotNull
            @Min(1)
            Integer quantity,

            @Schema(description = "The option to buy (V19) - REQUIRED when the listing has "
                    + "`hasVariants: true` (one of its `variants[].id`), omitted otherwise. Two "
                    + "options of one listing are two lines.",
                    example = "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352", nullable = true)
            // Component-level NON_NULL: a line without an option serialises to
            // exactly the pre-V19 bytes, so an idempotent retry that straddles
            // the deploy keeps its fingerprint (the collectionPoints technique).
            @JsonInclude(JsonInclude.Include.NON_NULL)
            UUID variantId) {

        /** A line on a listing without options — the pre-V19 shape. */
        public Item(UUID listingId, Integer quantity) {
            this(listingId, quantity, null);
        }
    }

    public boolean sourcedFromCart() {
        return Boolean.TRUE.equals(fromCart);
    }
}
