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

        @Schema(description = "Distinct listings", example = "2")
        int lineCount,

        @Schema(description = "Total units", example = "3")
        int totalQuantity,

        @Schema(description = "Sum of the SELLABLE lines, in minor units", example = "4798")
        long subtotalCents,

        @Schema(description = "Delivery fee in minor units: the sum of each seller's fee to the "
                + "address's town (see deliveryFees). 0 for COLLECTION.", example = "800")
        long deliveryFeeCents,

        @Schema(description = "subtotalCents + deliveryFeeCents — what the payments service will "
                + "collect.", example = "4998")
        long totalCents,

        @Schema(example = "USD")
        String currency,

        @Schema(description = "The delivery method this quote priced", example = "DELIVERY")
        DeliveryMethod deliveryMethod,

        @Schema(description = "The methods this cell offers, so the app renders only what it can "
                + "actually pick")
        List<DeliveryMethod> deliveryMethods,

        @Schema(description = "Where it would be sent. Absent for COLLECTION.", nullable = true)
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

        @Schema(description = "DELIVERY only: each seller's delivery fee to the address's town. "
                + "One parcel per seller, so a seller's fee is the highest of their items' fees to "
                + "that town, not the sum. Empty for COLLECTION.")
        List<SellerDeliveryFee> deliveryFees,

        @Schema(description = "COLLECTION only: where each seller's goods would be collected — "
                + "the point the buyer chose, else the seller's default. A seller with no "
                + "collection point is listed without one: say \"arrange collection with the "
                + "seller\". Absent for DELIVERY.", nullable = true)
        List<SellerCollectionPoint> collectionPoints) {

    @Schema(description = "One seller's delivery fee on this checkout")
    public record SellerDeliveryFee(
            @Schema(example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54") java.util.UUID merchantId,
            @Schema(example = "800") long feeCents) {
    }
}
