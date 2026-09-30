package com.innbucks.marketplaceservice.customersupport.dto;

import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentResponse;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import com.innbucks.marketplaceservice.settlement.dto.SettlementResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "One order as the call center sees it: the buyer's view, each seller's parcel, the "
        + "money, and the full journal")
public record SupportOrderResponse(
        @Schema(description = "The order exactly as the buyer sees it in the app, action flags included")
        OrderResponse order,

        Buyer buyer,

        @Schema(description = "Every seller on the order, with their name")
        List<Seller> sellers,

        @Schema(description = "One card per parcel, as that parcel's seller sees it: tracking, "
                + "collection-code state, the last buyer notice and its outcome, the money and any dispute")
        List<MerchantFulfilmentResponse> parcels,

        @Schema(description = "One settlement per paid parcel: what the seller is owed, held or refunded")
        List<SettlementResponse> settlements,

        @Schema(description = "The order's journal, oldest first: payment, parcel and money events")
        List<TimelineEntry> timeline,

        SupportNotesSummary notes) {

    @Schema(description = "Who placed the order")
    public record Buyer(
            @Schema(example = "5e8a1c3d-7b2f-4a9e-8c6d-0f1e2d3c4b5a") UUID buyerUuid,
            @Schema(description = "The payer's number, in full", example = "+263772123456") String msisdn) {
    }

    @Schema(description = "A seller on the order")
    public record Seller(
            @Schema(example = "4b1c8e2d-9f3a-4c56-8b7e-1d2f3a4b5c6d") UUID merchantId,
            @Schema(description = "Null when nobody has named this seller", example = "Rudo Traders")
            String displayName) {
    }

    @Schema(description = "One row of the order's journal")
    public record TimelineEntry(
            Instant at,
            @Schema(description = "PAYMENT, FULFILMENT or SETTLEMENT", example = "FULFILMENT") String kind,
            @Schema(example = "PREPARING") String fromStatus,
            @Schema(example = "DISPATCHED") String toStatus,
            @Schema(description = "What the journal recorded, e.g. the seller's decline reason",
                    example = "Dispatched") String detail) {
    }
}
