package com.innbucks.marketplaceservice.order.dto;

import com.innbucks.marketplaceservice.order.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Buyer-facing order view. Also the shape stored verbatim on the idempotency
 * claim row, so a replayed POST returns exactly what the first execution
 * returned — keep it JSON-round-trippable (records + Instant only).
 */
@Schema(description = "A buyer order")
public record OrderResponse(

        @Schema(description = "Order id", example = "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e")
        UUID id,

        @Schema(description = "Payments-facing opaque reference", example = "MKT-4F9A1C22B7D3")
        String orderRef,

        OrderStatus status,

        @Schema(description = "Order total in minor units (cents)", example = "3550")
        long totalCents,

        @Schema(description = "ISO-4217 currency (the deployment cell's currency)", example = "USD")
        String currency,

        @Schema(description = "When the PENDING_PAYMENT stock hold lapses (UTC)",
                example = "2026-08-05T10:45:00Z")
        Instant expiresAt,

        @Schema(example = "2026-08-05T10:15:00Z")
        Instant createdAt,

        List<Line> items) {

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
