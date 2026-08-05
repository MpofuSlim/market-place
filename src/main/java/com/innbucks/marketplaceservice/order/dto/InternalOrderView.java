package com.innbucks.marketplaceservice.order.dto;

import com.innbucks.marketplaceservice.order.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * S2S order view for the platform payments service — keyed by the opaque
 * {@code orderRef}, never the internal id (mirrors the booking-service
 * contract). Carries exactly what payments needs to mint and reconcile a
 * payment: the amount to charge, the currency, the buyer contact, and how
 * long the stock hold lasts.
 */
@Schema(description = "Internal S2S order view (payments contract)")
public record InternalOrderView(

        @Schema(example = "MKT-4F9A1C22B7D3")
        String orderRef,

        OrderStatus status,

        @Schema(description = "Amount the payments service must collect, in cents", example = "3550")
        long totalCents,

        @Schema(example = "USD")
        String currency,

        @Schema(description = "Buyer contact in E.164", example = "+263771234567")
        String buyerMsisdn,

        @Schema(description = "When the stock hold lapses (UTC)", example = "2026-08-05T10:45:00Z")
        Instant expiresAt) {
}
