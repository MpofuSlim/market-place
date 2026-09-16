package com.innbucks.marketplaceservice.checkout.dto;

import com.innbucks.marketplaceservice.checkout.PaymentRail;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One way the buyer may pay, ready to render on a payment-method picker.
 *
 * <p>The copy lives HERE rather than in the app so that adding a rail to a
 * cell is a config change, not a mobile release — an app that hardcoded the
 * three labels would show a rail the cell cannot collect on, or hide one it
 * can.
 */
@Schema(description = "A payment method the buyer can choose for this order")
public record PaymentOption(

        @Schema(description = "Send this verbatim as `paymentRail` on POST /payments.",
                example = "INNBUCKS_CODE")
        PaymentRail rail,

        @Schema(description = "Short label for the picker", example = "InnBucks app")
        String label,

        @Schema(description = "One line telling the buyer what will happen when they choose it",
                example = "Approve the payment code in your InnBucks app.")
        String description,

        @Schema(description = "What the app must do after POST /payments returns, so the picker "
                + "can route to the right screen. `APPROVE_IN_APP` — show the code + QR from the "
                + "response and poll; `CARD_WIDGET` — render the returned COPYandPAY widget; "
                + "`PHONE_PROMPT` — tell the buyer to approve the PIN prompt on their phone and "
                + "poll. A client meeting an unrecognised value should poll and show a generic "
                + "\"complete your payment\" screen rather than fail.",
                example = "APPROVE_IN_APP",
                allowableValues = {"APPROVE_IN_APP", "CARD_WIDGET", "PHONE_PROMPT"})
        String completion) {

    /** Strings, not an enum, for the same reason {@code OrderLineRejection}'s
     *  reasons are: a client that meets an unrecognised one must render
     *  something sensible, and adding one must never be a breaking change. */
    public static final String COMPLETION_APPROVE_IN_APP = "APPROVE_IN_APP";
    public static final String COMPLETION_CARD_WIDGET = "CARD_WIDGET";
    public static final String COMPLETION_PHONE_PROMPT = "PHONE_PROMPT";

    public static PaymentOption of(PaymentRail rail) {
        return switch (rail) {
            case INNBUCKS_CODE -> new PaymentOption(rail, "InnBucks app",
                    "Approve the payment code in your InnBucks app.", COMPLETION_APPROVE_IN_APP);
            case ZIMSWITCH_CARD -> new PaymentOption(rail, "Debit or credit card",
                    "Pay with your card on a secure checkout page.", COMPLETION_CARD_WIDGET);
            case ECOCASH -> new PaymentOption(rail, "EcoCash",
                    "We send a PIN prompt to the phone on this order. Approve it to pay.",
                    COMPLETION_PHONE_PROMPT);
        };
    }
}
