package com.innbucks.marketplaceservice.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.fulfilment.DeliveryConfirmer;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One seller's parcel, as the BUYER sees it on their order.
 *
 * <p>Carries who is sending it, what is in it and where it has got to —
 * deliberately not the seller's own contact details or the courier's account
 * references, which are the seller's business.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One seller's parcel of an order, as the buyer sees it")
public record FulfilmentResponse(

        @Schema(example = "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31")
        UUID id,

        @Schema(description = "The seller shipping this parcel",
                example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        UUID merchantId,

        @Schema(description = "The seller's trading name, when the platform knows one",
                example = "Sunrise Electronics", nullable = true)
        String sellerName,

        @Schema(description = "For a COLLECTION order read DISPATCHED as \"ready to collect\" and "
                + "DELIVERED as \"collected\" — the order's `deliveryMethod` tells you which "
                + "wording to use.", example = "DISPATCHED")
        FulfilmentStatus status,

        @Schema(description = "What the seller said when they sent it — courier and waybill, or "
                + "where to collect from.", example = "Swift Couriers, waybill 88213", nullable = true)
        String dispatchNote,

        @Schema(example = "2026-09-15T09:20:00Z", nullable = true)
        Instant dispatchedAt,

        @Schema(example = "2026-09-16T14:05:00Z", nullable = true)
        Instant deliveredAt,

        @Schema(description = "Who closed it. BUYER means the buyer confirmed receipt.",
                example = "BUYER", nullable = true)
        DeliveryConfirmer deliveredBy,

        @Schema(description = "The order's lines that belong to this seller")
        List<OrderResponse.Line> items) {
}
