package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How a parcel ended, in the words a seller thinks in — and so why its money
 * behaves as it does. Derived from what the parcel already records, never
 * stored: a second column would be a second truth to disagree with.
 */
@Schema(description = "How the parcel was closed. BUYER_CONFIRMED and COLLECTION_CODE release "
        + "your money at once; SELLER_MARKED waits out the dispute window; NOT_COLLECTED, "
        + "CANNOT_SUPPLY and BUYER_CANCELLED send the money back to the buyer")
public enum ParcelCloseMethod {
    /** The buyer tapped "received" ({@code deliveredBy: BUYER}). */
    BUYER_CONFIRMED,
    /** The collection code was entered at the counter ({@code deliveredBy: RECIPIENT}). */
    COLLECTION_CODE,
    /** The seller marked a delivery delivered ({@code deliveredBy: MERCHANT}). */
    SELLER_MARKED,
    /** A collection that was ready at the counter and never picked up. */
    NOT_COLLECTED,
    /** The seller declined it before it left. */
    CANNOT_SUPPLY,
    /** The buyer called it off before it left (V16). */
    BUYER_CANCELLED;

    /** Null while the parcel is still open. */
    public static ParcelCloseMethod of(OrderFulfilment parcel, DeliveryMethod method) {
        if (parcel == null) {
            return null;
        }
        if (parcel.getStatus() == FulfilmentStatus.UNFULFILLED) {
            if (parcel.getUnfulfilledBy() == UnfulfilledBy.BUYER) {
                return BUYER_CANCELLED;
            }
            // Only a COLLECTION can be declined after it was set aside
            // (FulfilmentStateMachine), and that is exactly "never collected".
            return method == DeliveryMethod.COLLECTION && parcel.getDispatchedAt() != null
                    ? NOT_COLLECTED : CANNOT_SUPPLY;
        }
        if (parcel.getStatus() != FulfilmentStatus.DELIVERED || parcel.getDeliveredBy() == null) {
            return null;
        }
        return switch (parcel.getDeliveredBy()) {
            case BUYER -> BUYER_CONFIRMED;
            case RECIPIENT -> COLLECTION_CODE;
            case MERCHANT -> SELLER_MARKED;
        };
    }

    /** When it closed, for the same parcel. */
    public static java.time.Instant closedAt(OrderFulfilment parcel) {
        if (parcel == null) {
            return null;
        }
        return parcel.getStatus() == FulfilmentStatus.UNFULFILLED
                ? parcel.getUnfulfilledAt() : parcel.getDeliveredAt();
    }
}
