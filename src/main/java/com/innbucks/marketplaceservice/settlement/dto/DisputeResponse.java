package com.innbucks.marketplaceservice.settlement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.settlement.DisputeReason;
import com.innbucks.marketplaceservice.settlement.DisputeStatus;
import com.innbucks.marketplaceservice.settlement.MerchantSettlement;
import com.innbucks.marketplaceservice.settlement.SettlementDispute;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** One dispute, as the buyer sees it on their order and the operator sees it
 *  on the queue. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A dispute over one parcel")
public record DisputeResponse(

        @Schema(example = "5c8d1e2f-9a34-4b67-8c01-2d3e4f5a6b7c")
        UUID id,

        @Schema(example = "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e")
        UUID orderId,

        @Schema(example = "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31")
        UUID fulfilmentId,

        @Schema(example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        UUID merchantId,

        DisputeReason reason,

        @Schema(description = "The buyer's words (sanitized)", nullable = true)
        String detail,

        DisputeStatus status,

        @Schema(description = "The operator's note, once resolved", nullable = true)
        String resolutionNote,

        @Schema(description = "The frozen parcel's money, minor units - what a RELEASE clears "
                + "for the seller's next payout run or a REFUND returns to the buyer. Always the "
                + "WHOLE parcel: there are no partial outcomes.",
                example = "4798", nullable = true)
        Long netCents,

        @Schema(example = "USD", nullable = true)
        String currency,

        @Schema(example = "2026-09-15T10:00:00Z")
        Instant createdAt,

        @Schema(nullable = true)
        Instant resolvedAt) {

    public static DisputeResponse from(SettlementDispute d) {
        return from(d, null);
    }

    /** With the settlement in hand, the row also names the money at stake -
     *  the queue batch-loads settlements so this never costs a per-row read.
     *  A missing settlement (never expected) simply omits the amount rather
     *  than failing the whole page. */
    public static DisputeResponse from(SettlementDispute d, MerchantSettlement settlement) {
        return new DisputeResponse(d.getId(), d.getOrderId(), d.getFulfilmentId(),
                d.getMerchantId(), d.getReason(), d.getDetail(), d.getStatus(),
                d.getResolutionNote(),
                settlement == null ? null : settlement.getNetCents(),
                settlement == null ? null : settlement.getCurrency(),
                d.getCreatedAt(), d.getResolvedAt());
    }
}
