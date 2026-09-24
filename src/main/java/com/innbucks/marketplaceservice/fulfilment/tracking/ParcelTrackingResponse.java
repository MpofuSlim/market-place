package com.innbucks.marketplaceservice.fulfilment.tracking;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.dto.FulfilmentDestination;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One parcel's tracking screen, as the BUYER sees it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Tracking for one parcel of the buyer's order")
public record ParcelTrackingResponse(

        @Schema(example = "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31")
        UUID fulfilmentId,

        @Schema(example = "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e")
        UUID orderId,

        @Schema(example = "MKT-4F9A1C22B7D3")
        String orderRef,

        @Schema(example = "TRK-7F3K9Q2M4X")
        String trackingCode,

        @Schema(example = "DELIVERY")
        DeliveryMethod deliveryMethod,

        @Schema(example = "DISPATCHED")
        TrackingStatus trackingStatus,

        @Schema(description = "Every stage this parcel has reached, oldest first")
        List<Stage> timeline,

        @Schema(description = "Where it is going (DELIVERY only)", nullable = true)
        FulfilmentDestination destination,

        @Schema(description = "Where the courier last reported it. Present only while a DELIVERY "
                + "parcel is DISPATCHED and the courier has posted a position; gone once it is "
                + "delivered or cancelled. Show `recordedAt`'s age next to the pin.",
                nullable = true)
        ParcelLocation liveLocation,

        @Schema(description = "Why it was cancelled, in the words of whoever cancelled it "
                + "(CANCELLED only)", example = "Out of stock", nullable = true)
        String cancelledReason,

        @Schema(description = "Who cancelled it (CANCELLED only): BUYER when you did, SELLER when "
                + "they could not supply it or it was never collected", example = "SELLER",
                nullable = true)
        com.innbucks.marketplaceservice.fulfilment.UnfulfilledBy cancelledBy) {

    @Schema(description = "A stage the parcel reached, and when")
    public record Stage(
            @Schema(example = "DISPATCHED") TrackingStatus status,
            @Schema(example = "2026-09-24T09:20:00Z") Instant at) {
    }
}
