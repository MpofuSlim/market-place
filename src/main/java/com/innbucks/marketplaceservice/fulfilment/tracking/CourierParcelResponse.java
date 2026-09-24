package com.innbucks.marketplaceservice.fulfilment.tracking;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.fulfilment.dto.FulfilmentDestination;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One parcel on a courier's run. What a driver needs to deliver it — where it
 * goes, who to ring, what is in it — and nothing about the money: a courier
 * sees no prices, subtotals or settlement.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A parcel out for delivery, as the courier sees it")
public record CourierParcelResponse(

        @Schema(example = "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31")
        UUID fulfilmentId,

        @Schema(example = "TRK-7F3K9Q2M4X")
        String trackingCode,

        @Schema(example = "MKT-4F9A1C22B7D3")
        String orderRef,

        @Schema(example = "DISPATCHED")
        TrackingStatus trackingStatus,

        @Schema(example = "2026-09-24T09:20:00Z")
        Instant dispatchedAt,

        @Schema(description = "Where it goes and who to ring")
        FulfilmentDestination destination,

        @Schema(description = "What to hand over")
        List<Item> items,

        @Schema(description = "When your last position for this parcel was stored",
                example = "2026-09-24T12:14:05Z", nullable = true)
        Instant lastLocationAt) {

    @Schema(description = "One line to hand over")
    public record Item(
            @Schema(example = "Solar Lantern 20W") String title,
            @Schema(example = "2") int quantity) {
    }
}
