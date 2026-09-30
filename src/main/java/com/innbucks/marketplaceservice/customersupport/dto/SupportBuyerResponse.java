package com.innbucks.marketplaceservice.customersupport.dto;

import com.innbucks.marketplaceservice.delivery.dto.AddressResponse;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentResponse;
import com.innbucks.marketplaceservice.order.OrderStatus;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import com.innbucks.marketplaceservice.settlement.dto.DisputeResponse;
import com.innbucks.marketplaceservice.settlement.dto.SettlementResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A buyer as the call center sees them. Phone numbers are shown in FULL here:
 * an agent confirms who they are speaking to by them, and every view of this
 * record is logged. They are masked everywhere a record is shown to someone
 * other than the agent working the case (the activity and message feeds).
 */
@Schema(description = "A marketplace buyer, everything a support agent needs on one screen")
public record SupportBuyerResponse(
        @Schema(example = "5e8a1c3d-7b2f-4a9e-8c6d-0f1e2d3c4b5a")
        UUID buyerUuid,

        @Schema(description = "Every number this buyer has paid from, most recently used first")
        List<Phone> phones,

        OrderTotals orders,

        @Schema(description = "The 5 newest orders, exactly as the buyer sees them in the app; the rest "
                + "are GET /marketplace/support/buyers/{buyerUuid}/orders")
        List<OrderResponse> recentOrders,

        @Schema(description = "Parcels still PREPARING or DISPATCHED, oldest first, as each seller's card "
                + "shows them (money, dispute, collection-code and notice state included). At most 20.")
        List<MerchantFulfilmentResponse> openParcels,

        @Schema(description = "The buyer's disputes, newest first. At most 20.")
        List<DisputeResponse> disputes,

        @Schema(description = "Money coming back to the buyer: settlements REFUND_DUE (decided, not yet "
                + "transferred) or REFUNDED (sent, with the transfer reference). Newest first, at most 20.")
        List<SettlementResponse> refunds,

        List<AddressResponse> addresses,

        Engagement engagement,

        SupportNotesSummary notes,

        @Schema(description = "Messages support has sent this buyer, including about any of their orders: the total and the newest few")
        SupportMessagesSummary messages) {

    @Schema(description = "A number the buyer has paid from")
    public record Phone(
            @Schema(example = "+263772123456") String msisdn,
            @Schema(example = "3") long orders,
            Instant lastUsedAt) {
    }

    @Schema(description = "The buyer's orders counted by status")
    public record OrderTotals(
            @Schema(example = "4") long total,
            Instant lastOrderAt,
            List<StatusTotal> byStatus) {
    }

    @Schema(description = "Orders in one status and what they came to")
    public record StatusTotal(
            OrderStatus status,
            @Schema(example = "3") long orders,
            @Schema(description = "Sum of the orders' totals, minor units", example = "23691") long totalCents) {
    }

    @Schema(description = "What else the buyer has done here")
    public record Engagement(
            @Schema(description = "Lines in the basket right now", example = "2") long basketLines,
            @Schema(example = "5") long favourites,
            @Schema(example = "1") long reviews,
            @Schema(description = "Listings this buyer has reported", example = "0") long reportsFiled) {
    }
}
