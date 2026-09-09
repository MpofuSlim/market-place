package com.innbucks.marketplaceservice.seller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Body for a seller decision.
 *
 * <p>{@code note} is optional at the type level and REQUIRED by the service on
 * reject and suspend — a seller told only "no" cannot fix anything, and a
 * second admin cannot see what a colleague decided. Approve and reinstate take
 * it optionally.
 *
 * <p>{@code displayName} is only read on approve: naming a seller is part of
 * vouching for them, and it is the trading name the badge shows.
 */
@Schema(description = "A SUPER_ADMIN's decision on a seller")
public record SellerDecisionRequest(

        @Size(max = 500, message = "note must be 500 characters or fewer")
        @Schema(example = "Business verification outstanding - please complete it and re-apply.",
                description = "Why. REQUIRED on reject and suspend; optional on approve/reinstate.",
                nullable = true)
        String note,

        @Size(max = 120, message = "displayName must be 120 characters or fewer")
        @Schema(example = "Rudo Traders",
                description = "Trading name for the buyer-facing badge. Read on APPROVE only; "
                        + "omit to leave the existing name unchanged.",
                nullable = true)
        String displayName
) { }
