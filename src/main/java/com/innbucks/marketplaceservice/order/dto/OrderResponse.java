package com.innbucks.marketplaceservice.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.checkout.dto.PaymentInstruction;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.fulfilment.dto.FulfilmentDestination;
import com.innbucks.marketplaceservice.fulfilment.dto.FulfilmentResponse;
import com.innbucks.marketplaceservice.order.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Buyer-facing order view — the whole journey of one order in one response:
 * what was bought, what it cost broken down, where it is going, how to pay for
 * it while it is unpaid, and where each seller's parcel has got to once it is.
 *
 * <p>Also the shape stored verbatim on the idempotency claim row, so a replayed
 * POST returns exactly what the first execution returned — keep it
 * JSON-round-trippable (records, enums and {@code Instant} only).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A buyer order")
public record OrderResponse(

        @Schema(description = "Order id", example = "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e")
        UUID id,

        @Schema(description = "Payments-facing opaque reference", example = "MKT-4F9A1C22B7D3")
        String orderRef,

        OrderStatus status,

        @Schema(description = "Sum of the lines, in minor units (cents)", example = "4798")
        long subtotalCents,

        @Schema(description = "Delivery fee in minor units — 0 for COLLECTION", example = "200")
        long deliveryFeeCents,

        @Schema(description = "subtotalCents + deliveryFeeCents — what the payments service "
                + "collects", example = "4998")
        long totalCents,

        @Schema(description = "ISO-4217 currency (the deployment cell's currency)", example = "USD")
        String currency,

        @Schema(description = "How the buyer receives the goods", example = "DELIVERY")
        DeliveryMethod deliveryMethod,

        @Schema(description = "Where it is being sent, as captured at order time. Absent for a "
                + "COLLECTION order.", nullable = true)
        FulfilmentDestination deliveryAddress,

        @Schema(description = "When the PENDING_PAYMENT stock hold lapses (UTC)",
                example = "2026-09-14T11:32:44Z")
        Instant expiresAt,

        @Schema(example = "2026-09-14T11:02:44Z")
        Instant createdAt,

        @Schema(description = "When payment was confirmed. Absent until then.",
                example = "2026-09-14T11:20:10Z", nullable = true)
        Instant paidAt,

        List<Line> items,

        @Schema(description = "How to settle this order. Present ONLY while it is awaiting "
                + "payment — marketplace-service does not collect money, and this block names the "
                + "payments-service call and the rails this cell can collect on so the app does "
                + "not carry that knowledge itself.", nullable = true)
        PaymentInstruction payment,

        @Schema(description = "The order's fulfilment summary: the LEAST advanced of its parcels, "
                + "so DELIVERED always means everything arrived. Absent until the order is paid "
                + "(and on a cancelled or expired order) — there is nothing to fulfil before the "
                + "money moves, and a PREPARING there would claim a seller was packing goods "
                + "nobody has paid for.", example = "DISPATCHED", nullable = true)
        FulfilmentStatus fulfilmentStatus,

        @Schema(description = "One parcel per selling merchant, each moving on its own clock. "
                + "Empty until the order is paid.")
        List<FulfilmentResponse> fulfilments,

        @Schema(description = "Who this order was bought for, when it is a gift. Absent on an "
                + "order bought for oneself. Their number is returned in full here and nowhere "
                + "else — this surface is the buyer reading back what they themselves typed.",
                nullable = true)
        Recipient recipient) {

    @Schema(description = "The person this order was bought for")
    public record Recipient(

            @Schema(example = "Gogo Chipo Moyo")
            String name,

            @Schema(description = "E.164, as normalised on the way in. Null when the buyer named "
                    + "a recipient without a number — nobody is messaged in that case and the "
                    + "buyer passes the collection code on themselves.",
                    example = "+263772345678", nullable = true)
            String msisdn,

            @Schema(example = "Happy birthday Gogo, love from Tari", nullable = true)
            String message) {
    }

    @Schema(description = "One order line, priced from the listing at order time")
    public record Line(

            @Schema(example = "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d")
            UUID listingId,

            @Schema(description = "Listing title at order time", example = "Solar Lantern 20W")
            String titleSnapshot,

            @Schema(description = "Unit price in cents at order time", example = "1550")
            long unitPriceCents,

            @Schema(example = "2")
            int quantity,

            @Schema(description = "unitPriceCents * quantity, in cents", example = "3100")
            long lineTotalCents) {
    }
}
