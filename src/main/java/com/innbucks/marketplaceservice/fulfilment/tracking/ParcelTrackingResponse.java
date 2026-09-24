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
        com.innbucks.marketplaceservice.fulfilment.UnfulfilledBy cancelledBy,

        @Schema(description = "COLLECTION only: where to collect it, as copied when the order was "
                + "placed, with the point's current opening hours (`openNow` in local time). "
                + "Absent when the seller had no collection point — arrange it with them.",
                nullable = true)
        com.innbucks.marketplaceservice.pickup.dto.CollectionPointResponse collectionPoint,

        @Schema(description = "What the order's buyer may do with this parcel right now, decided "
                + "by the server's own rules - show exactly these actions. (On an operator's read "
                + "of the order they still describe the buyer.)")
        com.innbucks.marketplaceservice.fulfilment.dto.ParcelActions actions,

        @Schema(description = "When the goods reached the buyer's side on evidence: your own "
                + "\"I have received it\", or the collection code redeemed at the counter. Absent "
                + "while the parcel is open, and absent when the SELLER marked it delivered - that "
                + "is their word, which you can still dispute (see `disputableUntil`).",
                example = "2026-09-16T15:40:00Z", nullable = true)
        Instant receivedAt,

        @Schema(description = "When the parcel finished, whichever way (delivered, collected or "
                + "cancelled). Absent while it is still open - present means finished.",
                example = "2026-09-16T15:40:00Z", nullable = true)
        Instant closedAt,

        @Schema(description = "How it finished: BUYER_CONFIRMED (you confirmed receipt), "
                + "COLLECTION_CODE (your code was redeemed at the counter), SELLER_MARKED (the "
                + "seller marked a delivery delivered), NOT_COLLECTED, CANNOT_SUPPLY (the seller "
                + "declined it), BUYER_CANCELLED. Absent while open.",
                example = "COLLECTION_CODE", nullable = true)
        com.innbucks.marketplaceservice.fulfilment.ParcelCloseMethod closedBy,

        @Schema(description = "The last moment to report a problem with a DELIVERED parcel (the "
                + "delivery time plus this market's dispute window) - AT THE LATEST: once you "
                + "confirm receipt the seller can be paid sooner, and a paid-out parcel can no "
                + "longer be disputed, so always read `actions.canDispute`. Present only while "
                + "that flag is true and the parcel has been delivered; an undelivered parcel can "
                + "be disputed with no deadline.",
                example = "2026-09-23T15:40:00Z", nullable = true)
        Instant disputableUntil,

        @Schema(description = "When your payment is released to the seller automatically, unless "
                + "you report a problem first. The clock runs only after the seller marked a "
                + "delivery delivered; it is set then, never shorter than the dispute window in "
                + "force at that moment, so it is normally the same as `disputableUntil` or "
                + "later. Show the report button from `actions.canDispute`. Absent when there "
                + "is no clock: the parcel is not delivered yet, your own confirmation or a "
                + "collection code already released it, or the money is disputed or being "
                + "refunded.",
                example = "2026-09-23T15:40:00Z", nullable = true)
        Instant paymentReleasesAt) {

    @Schema(description = "A stage the parcel reached, and when")
    public record Stage(
            @Schema(example = "DISPATCHED") TrackingStatus status,
            @Schema(example = "2026-09-24T09:20:00Z") Instant at) {
    }
}
