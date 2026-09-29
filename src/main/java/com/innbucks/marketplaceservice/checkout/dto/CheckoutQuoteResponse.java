package com.innbucks.marketplaceservice.checkout.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.pickup.dto.SellerCollectionPoint;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.delivery.dto.AddressResponse;
import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The checkout screen in one response: what is being bought, what it costs
 * broken down, where it is going, and how it can be paid for.
 *
 * <p><b>Reserves nothing and changes nothing.</b> The only way to find out an
 * item was out of stock used to be to CREATE an order — which reserved stock as
 * a side effect, so "let me just check" cost a merchant's inventory a hold.
 * Quoting is free and repeatable; the order is the commitment.
 *
 * <p>The totals here are what {@code POST /marketplace/orders} will produce for
 * the same body, as long as nothing sells out in between. They are not a
 * promise — the order recomputes from the listings itself, because a quote a
 * client could replay is a price a client could choose.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A priced, totalled checkout preview. Reserves no stock.")
public record CheckoutQuoteResponse(

        @Schema(description = "The basket, priced live, in the order it was given")
        List<PricedLineResponse> items,

        @Schema(description = "Distinct lines - one listing in two sizes is two lines", example = "2")
        int lineCount,

        @Schema(description = "Total units", example = "3")
        int totalQuantity,

        @Schema(description = "Sum of the SELLABLE lines, in minor units", example = "4798")
        long subtotalCents,

        @Schema(description = "Delivery fee in minor units: the sum of each DELIVERING seller's fee "
                + "to the address's town (see deliveryFees). 0 when nobody delivers.", example = "800")
        long deliveryFeeCents,

        @Schema(description = "subtotalCents + deliveryFeeCents — what the payments service will "
                + "collect.", example = "4998")
        long totalCents,

        @Schema(example = "USD")
        String currency,

        @Schema(description = "The delivery method this quote priced - with `sellerDeliveryMethods`, "
                + "the SUMMARY: DELIVERY when any seller delivers, else COLLECTION. Each seller's "
                + "own method is `sellers[].deliveryMethod`.", example = "DELIVERY")
        DeliveryMethod deliveryMethod,

        @Schema(description = "The methods this cell offers, so the app renders only what it can "
                + "actually pick")
        List<DeliveryMethod> deliveryMethods,

        @Schema(description = "Where it would be sent - present exactly when some seller delivers.",
                nullable = true)
        AddressResponse deliveryAddress,

        @Schema(description = "Every line that would be refused, so the whole correction can be "
                + "made in one pass. Absent when there are none.", nullable = true)
        List<OrderLineRejection> rejections,

        @Schema(description = "True when POST /marketplace/orders with this body would be "
                + "accepted. Gate the Place Order button on this.", example = "true")
        boolean checkoutReady,

        @Schema(description = "The payment rails this cell can collect on, in the order to offer "
                + "them — the same list the order's `payment` block will carry.")
        List<PaymentOption> paymentMethods,

        @Schema(description = "Each DELIVERING seller's delivery fee to the address's town. One "
                + "parcel per seller, so a seller's fee is the highest of their items' fees to "
                + "that town, not the sum. Empty when nobody delivers; a collecting seller is "
                + "never listed.")
        List<SellerDeliveryFee> deliveryFees,

        @Schema(description = "Where each COLLECTING seller's goods would be collected — the point "
                + "the buyer chose, else the seller's default. A seller with no collection point is "
                + "listed without one: say \"arrange collection with the seller\". Only sellers "
                + "priced with COLLECTION who OFFER it are listed (V20): a delivery-only seller is "
                + "never here (their lines are in `rejections` as `COLLECTION_NOT_OFFERED`), nor "
                + "is a seller chosen for DELIVERY in `sellerDeliveryMethods`. Present when some "
                + "seller collects; absent when nobody does.", nullable = true)
        List<SellerCollectionPoint> collectionPoints,

        @Schema(description = "V20: every seller with an item on sale in the basket, in basket "
                + "order - the method this quote priced them with, and the methods that could "
                + "reach the buyer from them at all (`availableMethods`). Render Deliver / "
                + "Collect per seller from `availableMethods` instead of working it out: "
                + "COLLECTION is listed when the cell offers it and the seller collects; DELIVERY "
                + "when the cell offers it and every one of the seller's items on sale is "
                + "delivered to `availabilityTownCode` (with no town known, delivered somewhere). "
                + "Always present on a quote; empty when nothing in the basket is on sale.")
        List<QuoteSeller> sellers,

        @Schema(description = "V20: the town `sellers[].availableMethods` judged DELIVERY against "
                + "- the destination's town on a DELIVERY quote; on a COLLECTION quote the town of "
                + "the named `deliveryAddressId`, else of the buyer's default address. Absent when "
                + "no town is known (no saved address, or one saved before towns existed); DELIVERY "
                + "then means \"delivers somewhere\".", example = "harare", nullable = true)
        String availabilityTownCode) {

    /** One seller's share of the quote (V20). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "One seller in the basket: how this quote delivers their goods, and how "
            + "it could.")
    public record QuoteSeller(
            @Schema(example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54") java.util.UUID merchantId,

            @Schema(description = "The method this quote priced the seller's goods with: their "
                    + "entry in `sellerDeliveryMethods`, else the request's `deliveryMethod`",
                    example = "DELIVERY")
            DeliveryMethod deliveryMethod,

            @Schema(description = "The methods that could reach the buyer from this seller, in the "
                    + "cell's order. A delivery-only seller never lists COLLECTION; a seller who does "
                    + "not deliver every item to the town never lists DELIVERY. Empty when neither "
                    + "can - drop their items.", example = "[\"DELIVERY\"]")
            List<DeliveryMethod> availableMethods,

            @Schema(description = "DELIVERY only: this seller's fee to the town (their dearest "
                    + "item's, never the sum) - the same figure as in `deliveryFees`. Absent for "
                    + "COLLECTION, and when none of their BUYABLE items (those with no other issue, "
                    + "such as out of stock or an option to pick) is delivered there - so a seller "
                    + "can list DELIVERY in `availableMethods` with no fee until those lines are "
                    + "fixed.",
                    example = "300", nullable = true)
            Long deliveryFeeCents) {
    }

    @Schema(description = "One seller's delivery fee on this checkout")
    public record SellerDeliveryFee(
            @Schema(example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54") java.util.UUID merchantId,
            @Schema(example = "800") long feeCents) {
    }
}
