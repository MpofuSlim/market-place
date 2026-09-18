package com.innbucks.marketplaceservice.settlement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.settlement.MerchantSettlement;
import com.innbucks.marketplaceservice.settlement.SettlementStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** One parcel's money, as the seller (or a fleet admin) sees it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One parcel's escrow settlement")
public record SettlementResponse(

        @Schema(example = "9d2f7a10-3b64-4c8e-a1f5-6e7b8c9d0a12")
        UUID id,

        @Schema(example = "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e")
        UUID orderId,

        @Schema(description = "The parcel this money follows", example = "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31")
        UUID fulfilmentId,

        @Schema(example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        UUID merchantId,

        SettlementStatus status,

        @Schema(description = "This seller's line totals on the order, minor units", example = "4798")
        long grossCents,

        @Schema(description = "Platform commission withheld (0 until the platform charges one)",
                example = "0")
        long commissionCents,

        @Schema(description = "What the seller is owed: gross - commission", example = "4798")
        long netCents,

        @Schema(example = "USD")
        String currency,

        @Schema(description = "When a seller-closed delivery's grace lapses and the money clears "
                + "on its own. Absent outside that wait.", nullable = true)
        Instant releasableAt,

        @Schema(description = "When the money cleared for payout", nullable = true)
        Instant releasedAt,

        @Schema(description = "When the operator paid it", nullable = true)
        Instant paidOutAt,

        @Schema(description = "The payout run's reference — what \"where is my money?\" is "
                + "answered with", example = "PAYOUT-2026-09-30-01", nullable = true)
        String payoutReference,

        @Schema(description = "When this money was queued to go back to the buyer, because the "
                + "seller declared the parcel unfulfillable. Present on REFUND_DUE and on the "
                + "REFUNDED rows that came through it.",
                example = "2026-09-18T09:15:00Z", nullable = true)
        Instant refundDueAt,

        @Schema(nullable = true)
        Instant refundedAt,

        @Schema(description = "The operator's transfer reference for the refund they executed",
                example = "RFND-2026-09-30-07", nullable = true)
        String refundReference,

        @Schema(example = "2026-09-14T11:20:10Z")
        Instant createdAt) {

    public static SettlementResponse from(MerchantSettlement s) {
        return new SettlementResponse(s.getId(), s.getOrderId(), s.getFulfilmentId(),
                s.getMerchantId(), s.getStatus(), s.getGrossCents(), s.getCommissionCents(),
                s.getNetCents(), s.getCurrency(), s.getReleasableAt(), s.getReleasedAt(),
                s.getPaidOutAt(), s.getPayoutReference(), s.getRefundDueAt(), s.getRefundedAt(),
                s.getRefundReference(), s.getCreatedAt());
    }
}
