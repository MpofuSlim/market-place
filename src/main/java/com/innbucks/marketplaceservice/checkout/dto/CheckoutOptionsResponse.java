package com.innbucks.marketplaceservice.checkout.dto;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What this cell offers at checkout — the static picture an app can fetch once
 * and cache for the session.
 *
 * <p>It exists so a mobile binary stops carrying per-cell knowledge it cannot
 * be right about: which payment rails are provisioned, whether this market has
 * a courier arrangement, and what delivery costs are all deployment facts, and
 * a shipped app that assumed them offers a buyer a method that dead-ends in
 * another service's 503.
 */
@Schema(description = "The delivery and payment options this cell offers")
public record CheckoutOptionsResponse(

        @Schema(description = "How goods can be received here, in the order to offer them")
        List<DeliveryMethod> deliveryMethods,

        @Schema(description = "Flat fee added to a DELIVERY order, in minor units. 0 on a cell "
                + "that has not set one. COLLECTION never pays it.", example = "200")
        long deliveryFeeCents,

        @Schema(example = "USD")
        String currency,

        @Schema(description = "The payment rails this cell can actually collect on")
        List<PaymentOption> paymentMethods,

        @Schema(description = "Where an order is paid — a DIFFERENT service. Marketplace-service "
                + "never collects money.", example = "POST /payments")
        String paymentEndpoint,

        @Schema(description = "Send as `orderType` on that request, with the order's `orderRef`.",
                example = "MARKETPLACE")
        String paymentOrderType) {
}
