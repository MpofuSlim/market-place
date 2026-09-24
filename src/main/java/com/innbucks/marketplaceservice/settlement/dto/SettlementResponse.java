package com.innbucks.marketplaceservice.settlement.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.fulfilment.ParcelCloseMethod;
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

        @Schema(description = "What the buyer paid for this parcel: this seller's line totals "
                + "PLUS their delivery fee, minor units", example = "5598")
        long grossCents,

        @Schema(description = "Platform commission withheld (0 until the platform charges one)",
                example = "0")
        long commissionCents,

        @Schema(description = "What the seller is owed: gross - commission", example = "5598")
        long netCents,

        @Schema(description = "The delivery fee inside grossCents (0 for collection). "
                + "Commission is never charged on it.", example = "800")
        long deliveryFeeCents,

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
        Instant createdAt,

        @Schema(description = "The order reference the buyer quotes", example = "MKT-4F9A1C22B7D3")
        String orderRef,

        @Schema(description = "What was in your parcel, at a glance",
                example = "2 x Solar Lantern 20W, 1 x Garden Hose")
        String itemSummary,

        @Schema(description = "How the parcel closed - and so why this money behaves as it does. "
                + "Absent while the parcel is open.", nullable = true)
        ParcelCloseMethod closedBy,

        @Schema(description = "When the parcel closed", example = "2026-09-16T14:05:00Z",
                nullable = true)
        Instant closedAt,

        @Schema(description = "Why the money went back to the buyer: your reason when you "
                + "declined the parcel, or the operator's note when a dispute was decided for the "
                + "buyer. Present on REFUND_DUE and REFUNDED rows.",
                example = "Out of stock - the last one was damaged in storage", nullable = true)
        String refundReason,

        @Schema(description = "The buyer's dispute on this parcel, when there is one",
                nullable = true)
        ParcelDisputeSummary dispute) {

    /** What the ledger row alone cannot say; built in one batch per page. */
    public record Context(String orderRef, String itemSummary, ParcelCloseMethod closedBy,
                         Instant closedAt, String refundReason, ParcelDisputeSummary dispute) {
        static final Context NONE = new Context(null, null, null, null, null, null);
    }

    public static SettlementResponse from(MerchantSettlement s) {
        return from(s, Context.NONE);
    }

    public static SettlementResponse from(MerchantSettlement s, Context context) {
        return new SettlementResponse(s.getId(), s.getOrderId(), s.getFulfilmentId(),
                s.getMerchantId(), s.getStatus(), s.getGrossCents(), s.getCommissionCents(),
                s.getNetCents(), s.getDeliveryFeeCents(), s.getCurrency(), s.getReleasableAt(),
                s.getReleasedAt(), s.getPaidOutAt(), s.getPayoutReference(), s.getRefundDueAt(),
                s.getRefundedAt(), s.getRefundReference(), s.getCreatedAt(),
                context.orderRef(), context.itemSummary(), context.closedBy(), context.closedAt(),
                context.refundReason(), context.dispute());
    }
}
