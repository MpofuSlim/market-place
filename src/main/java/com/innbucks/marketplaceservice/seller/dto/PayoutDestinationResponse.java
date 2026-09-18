package com.innbucks.marketplaceservice.seller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.seller.MarketplaceSeller;
import com.innbucks.marketplaceservice.seller.PayoutMethod;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A seller's payout destination, read back in full.
 *
 * <p><b>Nothing here is masked, deliberately.</b> This surface is reachable by
 * exactly two callers: the seller reading their OWN destination, and a
 * SUPER_ADMIN. Masking an account number for the person who typed it in serves
 * nobody and makes "is this the right account?" impossible to answer — which is
 * the one question the screen exists for. It is never assembled onto the public
 * merchant profile, which builds its own DTO field by field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Where this seller's released money is sent. `configured: false` with "
        + "everything else absent means none is on file yet.")
public record PayoutDestinationResponse(

        @Schema(example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        UUID merchantId,

        @Schema(description = "False when no destination is on file. The seller can still sell "
                + "and still accrues released money — it simply cannot be sent anywhere until "
                + "they provide one.", example = "true")
        boolean configured,

        @Schema(nullable = true, example = "MOBILE_MONEY")
        PayoutMethod method,

        @Schema(nullable = true, example = "Rudo Chikwanha")
        String accountName,

        @Schema(description = "MOBILE_MONEY only, E.164", nullable = true, example = "+263771234567")
        String msisdn,

        @Schema(description = "BANK only", nullable = true, example = "CBZ Bank")
        String bankName,

        @Schema(description = "BANK only", nullable = true, example = "01123456789012")
        String accountNumber,

        @Schema(description = "When the destination last changed", nullable = true,
                example = "2026-09-18T09:15:00Z")
        Instant updatedAt,

        @Schema(description = "Who last changed it — the seller, or an admin acting for them",
                nullable = true)
        UUID updatedBy) {

    public static PayoutDestinationResponse from(MarketplaceSeller s) {
        // An absent destination is a normal 200, not a 404: the seller exists,
        // and "you have not set one" is the answer the screen needs in order to
        // ask for one. A 404 would read as "no such seller".
        if (!s.hasPayoutDestination()) {
            return new PayoutDestinationResponse(s.getMerchantId(), false,
                    null, null, null, null, null, null, null);
        }
        return new PayoutDestinationResponse(
                s.getMerchantId(),
                true,
                s.getPayoutMethod(),
                s.getPayoutAccountName(),
                s.getPayoutMsisdn(),
                s.getPayoutBankName(),
                s.getPayoutAccountNumber(),
                s.getPayoutUpdatedAt(),
                s.getPayoutUpdatedBy());
    }
}
