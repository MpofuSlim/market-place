package com.innbucks.marketplaceservice.checkout;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A way the buyer can pay for a marketplace order.
 *
 * <p>These are payment-service's {@code PaymentRail} values, mirrored here so
 * the app can ask the marketplace "how may I pay for this order?" and get an
 * answer in the same vocabulary it must then send to {@code POST /payments}.
 * Marketplace-service never collects money and holds no payment credentials —
 * it only names the rails the cell is provisioned for
 * ({@code marketplace.checkout.payment-methods}).
 *
 * <p><b>Keep the names byte-identical to payment-service's enum.</b> The value
 * is passed straight through as {@code paymentRail} on the payment request; a
 * name that drifts is a rail the buyer picks and the payments service cannot
 * parse.
 */
@Schema(description = "A payment rail the buyer can settle an order on")
public enum PaymentRail {

    /** The default. An InnBucks 2D payment code + QR the customer approves in
     *  their own InnBucks app. Nothing is pushed to their phone. */
    INNBUCKS_CODE,

    /** ZimSwitch Online (COPYandPAY). The payment response carries the widget
     *  artifacts (checkoutId, script URL, integrity) for card entry. */
    ZIMSWITCH_CARD,

    /** EcoCash Instant Payment. A PIN prompt is pushed to the ORDER's phone
     *  number — nothing to render; show "approve on your phone" and poll. */
    ECOCASH
}
