package com.innbucks.marketplaceservice.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.DeliveryConfirmer;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.fulfilment.tracking.ParcelLocation;
import com.innbucks.marketplaceservice.fulfilment.tracking.TrackingStatus;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import com.innbucks.marketplaceservice.settlement.SettlementStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One parcel as the SELLER sees it — the work item on their queue.
 *
 * <p>A separate shape from the buyer's view rather than one with fields blanked
 * out, because the two audiences genuinely differ: this one carries the
 * DESTINATION and the buyer's phone number, which the seller needs in order to
 * ship and which nobody else may see. Serving one DTO to both is how that
 * detail eventually leaks onto the wrong surface.
 *
 * <p>It shows only THIS seller's lines and their subtotal — never the whole
 * order — so a seller in a multi-seller order learns nothing about what else
 * the buyer bought or what they paid in total.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One parcel on a seller's fulfilment queue")
public record MerchantFulfilmentResponse(

        @Schema(example = "3a7b19e4-8c25-4f6d-b019-5e2c7a4d8f31")
        UUID id,

        @Schema(example = "b4a8e2d1-7c3f-4b5a-9e6d-2f1a8c7b5d4e")
        UUID orderId,

        @Schema(description = "The buyer's reference for this order — quote it in any message "
                + "to them.", example = "MKT-4F9A1C22B7D3")
        String orderRef,

        @Schema(example = "7e2a9c41-5b8f-4d36-a1c9-8f3b6d2e7a54")
        UUID merchantId,

        @Schema(example = "PREPARING")
        FulfilmentStatus status,

        @Schema(description = "DELIVERY means ship it to `destination`; COLLECTION means hold it "
                + "for the buyer and mark it DISPATCHED when it is ready at the counter.",
                example = "DELIVERY")
        DeliveryMethod deliveryMethod,

        @Schema(description = "Where to send it. Absent for a COLLECTION order. This is the "
                + "destination AS IT WAS at order time — the buyer editing their address book "
                + "afterwards does not redirect a parcel already in flight.", nullable = true)
        FulfilmentDestination destination,

        @Schema(description = "Only this seller's lines of the order")
        List<OrderResponse.Line> items,

        @Schema(description = "Sum of this seller's lines, in minor units — NOT the order total.",
                example = "4798")
        long subtotalCents,

        @Schema(example = "USD")
        String currency,

        @Schema(description = "When the buyer paid — the clock the seller is being measured on",
                example = "2026-09-14T11:20:10Z", nullable = true)
        Instant paidAt,

        @Schema(example = "Swift Couriers, waybill 88213", nullable = true)
        String dispatchNote,

        @Schema(example = "2026-09-15T09:20:00Z", nullable = true)
        Instant dispatchedAt,

        @Schema(example = "2026-09-16T14:05:00Z", nullable = true)
        Instant deliveredAt,

        @Schema(description = "Who closed it", example = "BUYER", nullable = true)
        DeliveryConfirmer deliveredBy,

        @Schema(example = "2026-09-14T11:20:10Z")
        Instant createdAt,

        @Schema(description = "Where this parcel's money is in the escrow (V10): HELD until "
                + "delivery, RELEASABLE once earned, DISPUTED while a buyer's dispute is open, "
                + "PAID_OUT/REFUNDED once closed. Absent for parcels predating the escrow.",
                example = "HELD", nullable = true)
        SettlementStatus settlementStatus,

        @Schema(description = "What this parcel pays the seller once released (net of any "
                + "commission), minor units. Absent with settlementStatus.",
                example = "4798", nullable = true)
        Long settlementNetCents,

        @Schema(description = "Who is coming to collect, when the order was bought for someone "
                + "else. Their NAME only — the platform does not hand a seller a third party's "
                + "phone number, and the collection code is what actually proves entitlement. "
                + "Absent when the buyer is collecting themselves.",
                example = "Gogo Chipo Moyo", nullable = true)
        String collectorName,

        @Schema(description = "Whether this parcel has a live collection code waiting to be "
                + "redeemed. The code itself is NEVER shown to a seller — you verify one, you do "
                + "not read one.", example = "true")
        boolean collectCodeIssued,

        @Schema(description = "When you redeemed the collection code for this parcel",
                example = "2026-09-16T15:40:00Z", nullable = true)
        Instant collectCodeRedeemedAt,

        @Schema(description = "The reason you gave for not supplying this parcel",
                example = "Out of stock - the last one was damaged in storage", nullable = true)
        String unfulfilledReason,

        @Schema(example = "2026-09-18T09:15:00Z", nullable = true)
        Instant unfulfilledAt,

        @Schema(description = "The parcel's tracking code - search it with "
                + "GET /marketplace/fulfilments/tracking/{code}", example = "TRK-7F3K9Q2M4X")
        String trackingCode,

        @Schema(description = "RECEIVED / DISPATCHED / DELIVERED / CANCELLED - the tracker's "
                + "reading of `status`", example = "DISPATCHED")
        TrackingStatus trackingStatus,

        @Schema(description = "Your delivery fee for this parcel, minor units - part of "
                + "settlementNetCents. 0 for collection.", example = "800")
        long deliveryFeeCents,

        @Schema(description = "Where your courier last reported this parcel. Absent until they "
                + "post a position.", nullable = true)
        ParcelLocation lastLocation) {
}
