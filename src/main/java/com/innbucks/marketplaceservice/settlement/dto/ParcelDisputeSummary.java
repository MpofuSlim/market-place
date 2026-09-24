package com.innbucks.marketplaceservice.settlement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.settlement.DisputeReason;
import com.innbucks.marketplaceservice.settlement.DisputeStatus;
import com.innbucks.marketplaceservice.settlement.SettlementDispute;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * A parcel's dispute as the SELLER sees it: that there is one, why, and where
 * it stands. The buyer's own free-text detail is deliberately not carried —
 * the seller learns the category, the operator reads the words.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "The buyer's dispute on this parcel")
public record ParcelDisputeSummary(

        @Schema(description = "OPEN (money frozen until an operator decides), RELEASED (decided "
                + "for you) or REFUNDED (decided for the buyer)", example = "OPEN")
        DisputeStatus status,

        @Schema(example = "NOT_RECEIVED")
        DisputeReason reason,

        @Schema(example = "2026-09-20T10:00:00Z")
        Instant openedAt,

        @Schema(example = "2026-09-22T08:30:00Z", nullable = true)
        Instant resolvedAt) {

    public static ParcelDisputeSummary of(SettlementDispute dispute) {
        return dispute == null ? null : new ParcelDisputeSummary(dispute.getStatus(),
                dispute.getReason(), dispute.getCreatedAt(), dispute.getResolvedAt());
    }
}
