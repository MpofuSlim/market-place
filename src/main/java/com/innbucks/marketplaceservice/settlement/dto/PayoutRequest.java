package com.innbucks.marketplaceservice.settlement.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** One payout run: every RELEASABLE settlement of one merchant, under one
 *  reference — the shape finance actually pays in. */
@Schema(description = "Mark one merchant's releasable settlements paid, under one payout reference")
public record PayoutRequest(

        @Schema(example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        @NotNull
        UUID merchantId,

        @Schema(description = "The transfer/batch reference of the payment the operator made — "
                + "what a seller asking \"where is my money?\" is answered with.",
                example = "PAYOUT-2026-09-30-01")
        @NotBlank
        @Size(max = 64)
        String payoutReference) {
}
