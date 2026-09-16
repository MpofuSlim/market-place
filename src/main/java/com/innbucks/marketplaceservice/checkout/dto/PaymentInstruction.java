package com.innbucks.marketplaceservice.checkout.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Everything the app needs to start paying for an order, in one block on the
 * order itself.
 *
 * <p><b>Why this exists.</b> An order came back {@code PENDING_PAYMENT} and
 * said nothing about how to settle it: the app had to know, from its own
 * hardcoded knowledge, that money for a marketplace order is collected by a
 * DIFFERENT service, under {@code POST /payments}, addressed by
 * {@code orderType} + {@code orderRef} rather than by the order id it was just
 * handed. That is cross-service knowledge baked into a mobile binary, and it
 * is wrong the moment a cell enables a rail the app was not built with.
 *
 * <p>Marketplace-service still collects nothing and holds no payment
 * credentials — this block only names the call and the rails the cell is
 * provisioned for.
 */
@Schema(description = "How to pay for this order. Present only while it is awaiting payment.")
public record PaymentInstruction(

        @Schema(description = "The service that collects the money. Marketplace-service does not.",
                example = "POST /payments")
        String endpoint,

        @Schema(description = "Send as `orderType` on that request — never the order's id.",
                example = "MARKETPLACE")
        String orderType,

        @Schema(description = "Send as `orderRef` on that request.", example = "MKT-4F9A1C22B7D3")
        String orderRef,

        @Schema(description = "What will be collected, in minor units. The payments service reads "
                + "the amount from the order itself — this is here so the app can show it, not so "
                + "it can send it.", example = "4998")
        long amountCents,

        @Schema(example = "USD")
        String currency,

        @Schema(description = "Settle before this instant or the stock hold lapses and the order "
                + "expires. The payments service extends the hold to outlive the code it mints, so "
                + "a payment started just before this still completes.",
                example = "2026-09-14T11:32:44Z")
        Instant payBefore,

        @Schema(description = "The rails this cell can actually collect on, in the order to offer "
                + "them. Never assume the full set — a cell advertises only what it is "
                + "provisioned for.")
        List<PaymentOption> methods) {
}
