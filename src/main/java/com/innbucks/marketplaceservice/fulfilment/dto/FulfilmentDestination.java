package com.innbucks.marketplaceservice.fulfilment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.order.MarketOrder;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Where a parcel is going, read off the order's SNAPSHOT rather than the
 * buyer's address book.
 *
 * <p>That distinction is the whole point: the buyer can rename, edit or delete
 * the book entry the moment after ordering, and a seller reading through to it
 * would find a different street — or none — for a parcel already packed.
 *
 * <p>Shown on the buyer's own order and tracking views, to the fulfilling
 * seller of a DELIVERY parcel, to that seller's couriers and to fleet admins —
 * never on another seller's parcel, and never on a COLLECTION parcel. Only the
 * buyer's copies ({@link #forBuyer}, {@link #forBuyerParcel}) name the
 * address-book entry.
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
        String landmark,

        @Schema(description = "On the order and tracking views (the buyer's own, and an "
                + "operator's read of the order): the address-book entry this destination was "
                + "copied from, to link back to it. Provenance only - the entry may since have "
                + "been edited or deleted (GET /marketplace/addresses/{id} then 404s "
                + "address_not_found), and this destination is always what the order uses. Never "
                + "on a seller's or courier's copy.",
                example = "6f1c9d20-4a7e-4b83-9c5d-2e1f8a7b6c45", nullable = true)
        UUID addressId) {

    /**
     * Where ONE parcel is going: the order's destination for a DELIVERY
     * parcel, null for a COLLECTION one. The parcel's own method decides, not
     * the order's summary — on a mixed order the collected seller's card must
     * not carry the delivering seller's destination (the buyer's address and
     * phone are that seller's business only). The seller card's copy (whoever
     * reads it, an admin included) and the courier's: no {@code addressId}.
     */
    public static FulfilmentDestination forParcel(MarketOrder order, OrderFulfilment parcel) {
        return delivered(parcel) ? of(order, null) : null;
    }

    /** {@link #forParcel}, in the buyer's shape (with {@code addressId}) —
     *  the buyer's tracking screen for one parcel. */
    public static FulfilmentDestination forBuyerParcel(MarketOrder order, OrderFulfilment parcel) {
        return delivered(parcel) ? of(order, order.getDeliveryAddressId()) : null;
    }

    /**
     * The BUYER's own copy of the ORDER's destination, which also names the
     * address-book entry it came from. Null when no seller on the order
     * delivers (the V9 CHECK refuses a DELIVERY-summary order without one).
     * Kept off the seller's and courier's surfaces: the id means nothing to
     * anyone but the buyer (their book is theirs alone), and on a seller's
     * card it would let a seller correlate one buyer's orders by address
     * entry. A SUPER_ADMIN's oversight read of an order shares the buyer's
     * shape and so carries it — harmlessly, since that reader already sees the
     * buyer, the full address and every order, and cannot open the book.
     */
    public static FulfilmentDestination forBuyer(MarketOrder order) {
        return of(order, order.getDeliveryAddressId());
    }

    private static boolean delivered(OrderFulfilment parcel) {
        return parcel.getDeliveryMethod() == DeliveryMethod.DELIVERY;
    }

    private static FulfilmentDestination of(MarketOrder order, UUID addressId) {
        if (order.getDeliveryLine1() == null) {
            return null;
        }
        return new FulfilmentDestination(order.getDeliveryRecipientName(),
                order.getDeliveryRecipientMsisdn(), order.getDeliveryLine1(),
                order.getDeliveryLine2(), order.getDeliveryCity(), order.getDeliveryArea(),
                order.getDeliveryLandmark(), addressId);
    }
}
