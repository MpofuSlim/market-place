package com.innbucks.marketplaceservice.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.order.MarketOrder;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where a parcel is going, read off the order's SNAPSHOT rather than the
 * buyer's address book.
 *
 * <p>That distinction is the whole point: the buyer can rename, edit or delete
 * the book entry the moment after ordering, and a seller reading through to it
 * would find a different street — or none — for a parcel already packed.
 *
 * <p>Shown to the fulfilling seller and to fleet admins, never on a buyer's
 * public surface or another seller's parcel.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A parcel's destination, as captured at order time")
public record FulfilmentDestination(

        @Schema(example = "Tariro Moyo")
        String recipientName,

        @Schema(description = "The number to ring on arrival. E.164.", example = "+263771234567")
        String recipientMsisdn,

        @Schema(example = "14 Samora Machel Ave")
        String line1,

        @Schema(example = "Flat 3B", nullable = true)
        String line2,

        @Schema(example = "Harare")
        String city,

        @Schema(example = "Avondale", nullable = true)
        String area,

        @Schema(example = "Opposite the clinic, blue gate", nullable = true)
        String landmark) {

    /** Null for a COLLECTION order, which has no destination by construction —
     *  the V9 CHECK constraint refuses a DELIVERY order without one. */
    public static FulfilmentDestination from(MarketOrder order) {
        if (order.getDeliveryLine1() == null) {
            return null;
        }
        return new FulfilmentDestination(order.getDeliveryRecipientName(),
                order.getDeliveryRecipientMsisdn(), order.getDeliveryLine1(),
                order.getDeliveryLine2(), order.getDeliveryCity(), order.getDeliveryArea(),
                order.getDeliveryLandmark());
    }
}
