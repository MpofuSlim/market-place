package com.innbucks.marketplaceservice.fulfilment.tracking;

import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The parcel status as a person tracking it reads it: Received, Dispatched,
 * Delivered, Cancelled.
 *
 * <p>A PRESENTATION of {@link FulfilmentStatus}, never stored and never a
 * second state machine: {@code status} keeps its values on every response
 * (existing clients branch on them), and this is derived from it one-to-one.
 * Two vocabularies over one machine are safe; two machines are how a parcel
 * ends up "delivered" in one place and "cancelled" in another.
 */
@Schema(description = """
        The parcel as a tracker reads it:
        * `RECEIVED` — the seller has the paid order and is preparing it
        * `DISPATCHED` — on its way (DELIVERY) or ready at the counter (COLLECTION)
        * `DELIVERED` — handed over (collected, for COLLECTION)
        * `CANCELLED` — the seller could not supply it, or it was never collected; \
        the buyer's money for it is refunded""")
public enum TrackingStatus {
    RECEIVED,
    DISPATCHED,
    DELIVERED,
    CANCELLED;

    public static TrackingStatus of(FulfilmentStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case PREPARING -> RECEIVED;
            case DISPATCHED -> DISPATCHED;
            case DELIVERED -> DELIVERED;
            case UNFULFILLED -> CANCELLED;
        };
    }
}
