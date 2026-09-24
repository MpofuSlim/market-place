package com.innbucks.marketplaceservice.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointChoice;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Order creation payload. Deliberately carries NO prices — unit prices, the
 * delivery fee and the total are computed server-side from the listing rows and
 * the cell's configuration, so a client can never supply a price. Config-bound
 * caps (line count, per-line quantity) are enforced in the service, not here,
 * because they come from {@code marketplace.order.*} properties.
 *
 * <p>Same shape as {@code CheckoutQuoteRequest}, so a client can send the body
 * it just quoted with, unchanged.
 */
@Schema(description = "Order creation request. Prices are never accepted from the client — "
        + "totals are computed server-side from the listing rows.")
public record CreateOrderRequest(

        @Schema(description = "Buyer contact number, used ONLY when the caller's token carries no "
                + "phoneNumber claim. A CUSTOMER token's own phone always wins — the payer is the "
                + "caller, and on the EcoCash rail this number receives the PIN prompt. Normalised "
                + "to E.164 (default region = the deployment country).",
                example = "+263771234567", nullable = true)
        @Size(max = 32)
        String buyerMsisdn,

        @Schema(description = "Order the buyer's whole cart. Mutually exclusive with `items` — "
                + "sending both is refused rather than silently resolved, because a client that "
                + "believes it sent a Buy Now and got the whole cart has bought things the "
                + "shopper never confirmed. The ordered lines are removed from the cart once the "
                + "order commits; anything else stays.", example = "true", nullable = true)
        Boolean fromCart,

        @Schema(description = "Explicit order lines — one per listing, no duplicates. Mutually "
                + "exclusive with `fromCart`.", nullable = true)
        @Valid
        List<Item> items,

        @Schema(description = "How the buyer receives the goods. Defaults to COLLECTION — the same "
                + "thing an order with no delivery instruction has always meant here, so an "
                + "un-updated client keeps behaving identically. Delivery is an explicit choice: "
                + "it costs money and needs somewhere to go. A method the cell does not offer is "
                + "422 `delivery_method_unavailable`.", example = "DELIVERY", nullable = true)
        DeliveryMethod deliveryMethod,

        @Schema(description = "Which saved address to deliver to. Ignored for COLLECTION. Omitted "
                + "on a DELIVERY order means the buyer's default address; a buyer with no saved "
                + "address gets 400 `delivery_address_required` rather than an order with nowhere "
                + "to send it. The address is SNAPSHOT onto the order, so editing or deleting it "
                + "afterwards never redirects a parcel already in flight.",
                example = "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45", nullable = true)
        UUID deliveryAddressId,

        @Schema(description = "Who the order is FOR, when that is not the buyer — the diaspora "
                + "case: paying from abroad for someone at home. Omit it and the order is yours, "
                + "exactly as before. The recipient is NOT given an account and cannot see the "
                + "order; they are a name the goods are handed to and a number we can message "
                + "about it.", nullable = true)
        @Valid
        Recipient recipient,

        @Schema(description = "COLLECTION only: which of each seller's collection points to "
                + "collect from. A seller you do not name is collected from their DEFAULT point; "
                + "a seller with no points is arranged directly with them. The chosen point is "
                + "SNAPSHOT onto the order, so the seller editing or removing it afterwards never "
                + "moves your collection. Ignored for DELIVERY.", nullable = true)
        // NON_NULL keeps the idempotency fingerprint of a body that does not use
        // this field byte-identical to what it was before the field existed.
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Valid
        @Size(max = 50)
        List<CollectionPointChoice> collectionPoints) {

    /** The shape before collection points existed. */
    public CreateOrderRequest(String buyerMsisdn, Boolean fromCart, List<Item> items,
                              DeliveryMethod deliveryMethod, UUID deliveryAddressId,
                              Recipient recipient) {
        this(buyerMsisdn, fromCart, items, deliveryMethod, deliveryAddressId, recipient, null);
    }

    @Schema(description = "The person an order is bought for")
    public record Recipient(

            @Schema(description = "Their name, as it should read on the parcel and to the seller "
                    + "at the counter.", example = "Gogo Chipo Moyo")
            @NotBlank
            @Size(max = 120)
            String name,

            @Schema(description = "Their number, so the platform can tell them something is "
                    + "coming and send the collection code. Normalised to E.164 (default region = "
                    + "the deployment country). Optional — a recipient with no number is simply "
                    + "never messaged, and the buyer passes the code on themselves.",
                    example = "+263772345678", nullable = true)
            @Size(max = 32)
            String msisdn,

            @Schema(description = "The note that makes it a gift. Sanitized server-side and short "
                    + "because it rides an SMS.", example = "Happy birthday Gogo, love from Tari",
                    nullable = true)
            @Size(max = 200)
            String message) {
    }

    @Schema(description = "One order line")
    public record Item(

            @Schema(description = "Listing to buy", example = "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d")
            @NotNull
            UUID listingId,

            @Schema(description = "Units to buy (capped by marketplace.order.max-quantity-per-item)",
                    example = "2")
            @NotNull
            @Min(1)
            Integer quantity) {
    }

    public boolean sourcedFromCart() {
        return Boolean.TRUE.equals(fromCart);
    }
}
