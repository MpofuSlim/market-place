package com.innbucks.marketplaceservice.delivery;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * How the buyer takes possession of the goods (V9
 * {@code market_order.delivery_method}).
 *
 * <p>Two values, not a courier taxonomy: the platform books no couriers and
 * quotes no zones, so anything finer would be a promise it cannot keep. The
 * seller's own arrangement rides in the fulfilment parcel's dispatch note.
 */
@Schema(description = "How the buyer receives the order")
public enum DeliveryMethod {

    /** Sent to the address captured on the order. Requires a destination —
     *  the V9 CHECK constraint refuses a DELIVERY order without one. */
    DELIVERY,

    /** Collected from the seller. No address, and no delivery fee. */
    COLLECTION
}
