package com.innbucks.marketplaceservice.settlement.dto;

import com.innbucks.marketplaceservice.settlement.SettlementStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** "Where is my money" in one read: a seller's parcels and net totals grouped
 *  by escrow state. A status with nothing in it simply is not listed. */
@Schema(description = "One merchant's money grouped by escrow state")
public record SettlementSummaryResponse(

        @Schema(example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        UUID merchantId,

        @Schema(description = "Whether this seller has a payout destination on file (V13). False "
                + "means money can be released to them and then not sent anywhere — which is why "
                + "it rides the 'where is my money' read: this is the screen a seller is on when "
                + "the answer matters to them, and the only place they would learn they need to "
                + "provide one. Set it at PUT /marketplace/sellers/me/payout-destination.",
                example = "true")
        boolean payoutDestinationConfigured,

        List<Line> totals) {

    @Schema(description = "One escrow state's totals")
    public record Line(

            SettlementStatus status,

            @Schema(description = "Parcels in this state", example = "12")
            long parcels,

            @Schema(description = "Net minor units in this state", example = "185000")
            long netCents) {
    }
}
