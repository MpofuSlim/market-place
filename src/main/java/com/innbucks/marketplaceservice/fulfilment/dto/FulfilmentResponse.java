package com.innbucks.marketplaceservice.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.fulfilment.DeliveryConfirmer;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import com.innbucks.marketplaceservice.settlement.dto.DisputeResponse;
import com.innbucks.marketplaceservice.fulfilment.tracking.TrackingStatus;
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

        @Schema(description = "Who closed a DELIVERED parcel: BUYER (you confirmed receipt), "
                + "RECIPIENT (your collection code was redeemed at the counter) or MERCHANT (the "
                + "seller marked a delivery delivered - their word, which you can still dispute). "
                + "`closedBy` says the same in one field for every way a parcel can finish.",
                example = "BUYER", nullable = true)
        DeliveryConfirmer deliveredBy,

        @Schema(description = "The order's lines that belong to this seller")
        List<OrderResponse.Line> items,

        @Schema(description = "The buyer's dispute over this parcel, when one exists — its status "
                + "says whether it is still with the operators (OPEN) or how it ended "
                + "(RELEASED / REFUNDED). Absent when the parcel was never disputed.",
                nullable = true)
        DisputeResponse dispute,

        @Schema(description = "When a collection code was last minted for this parcel. It stays "
                + "after the parcel closes, so it does not by itself mean a live code - "
                + "`actions.canRequestCollectCode` says whether one can still be used. The code "
                + "itself is never returned here; mint a fresh one to see it again.",
                example = "2026-09-16T14:05:00Z", nullable = true)
        Instant collectCodeIssuedAt,

        @Schema(description = "When a seller redeemed the collection code — the moment the goods "
                + "changed hands, and the strongest evidence the platform holds that they did.",
                example = "2026-09-16T15:40:00Z", nullable = true)
        Instant collectCodeRedeemedAt,

        @Schema(description = "Why the parcel is not coming, in the words of whoever ended it "
                + "(see `unfulfilledBy`). Present only on an UNFULFILLED parcel — show a seller's "
                + "reason, because it is the whole explanation the buyer gets for goods that are "
                + "not coming.",
                example = "Out of stock - the last one was damaged in storage", nullable = true)
        String unfulfilledReason,

        @Schema(example = "2026-09-18T09:15:00Z", nullable = true)
        Instant unfulfilledAt,

        @Schema(description = "Who ended an UNFULFILLED parcel: BUYER when you cancelled it "
                + "(`unfulfilledReason` is then your own reason, if you gave one), SELLER when "
                + "they could not supply it or it was never collected. Absent otherwise.",
                example = "SELLER", nullable = true)
        com.innbucks.marketplaceservice.fulfilment.UnfulfilledBy unfulfilledBy,

        @Schema(description = "Quote this to the seller or support; the live tracking screen is "
                + "GET /marketplace/orders/{orderId}/fulfilments/{fulfilmentId}/tracking",
                example = "TRK-7F3K9Q2M4X")
        String trackingCode,

        @Schema(description = "RECEIVED / DISPATCHED / DELIVERED / CANCELLED — the tracker's "
                + "reading of `status`", example = "DISPATCHED")
        TrackingStatus trackingStatus,

        @Schema(description = "This seller's delivery fee on the order, minor units. 0 for "
                + "collection.", example = "800")
        long deliveryFeeCents,

        @Schema(description = "COLLECTION only: where to collect this parcel, as copied when the "
                + "order was placed (with the point's current opening hours). Absent when the "
                + "seller had no collection point — collection is then arranged with them.",
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
                + "delivery delivered, and never ends before `disputableUntil`. Absent when there "
                + "is no clock: the parcel is not delivered yet, your own confirmation or a "
                + "collection code already released it, or the money is disputed or being "
                + "refunded.",
                example = "2026-09-23T15:40:00Z", nullable = true)
        Instant paymentReleasesAt) {
}
