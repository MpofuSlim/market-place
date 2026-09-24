package com.innbucks.marketplaceservice.fulfilment.notice;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether that message actually reached the buyer. A failed message never
 * undoes the seller's action — which is exactly why the seller needs to see it
 * here: the action succeeded, the buyer may still not know.
 */
@Schema(description = "SMS or WHATSAPP = delivered to the gateway on that channel; FAILED = "
        + "every channel refused it (tell the buyer yourself); NOT_SENT = this cell sends no "
        + "such message, or the order has no number")
public enum BuyerNoticeOutcome {
    SMS,
    WHATSAPP,
    FAILED,
    NOT_SENT;

    /** The notification metric's outcome tag, read as a stored outcome. */
    public static BuyerNoticeOutcome fromMetricOutcome(String outcome) {
        return switch (outcome == null ? "" : outcome) {
            case "sent" -> SMS;
            case "fallback" -> WHATSAPP;
            case "failed" -> FAILED;
            default -> NOT_SENT;
        };
    }
}
