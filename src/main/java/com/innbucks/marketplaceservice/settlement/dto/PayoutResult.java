package com.innbucks.marketplaceservice.settlement.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** What one payout run covered. */
@Schema(description = "The result of one payout run")
public record PayoutResult(

        @Schema(example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        UUID merchantId,

        @Schema(description = "Settlements marked paid", example = "12")
        int parcels,

        @Schema(description = "Total net minor units this run covered", example = "185000")
        long totalNetCents,

        @Schema(example = "USD")
        String currency,

        @Schema(example = "PAYOUT-2026-09-30-01")
        String payoutReference) {
}
