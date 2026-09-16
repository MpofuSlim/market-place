package com.innbucks.marketplaceservice.fulfilment;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where one seller's parcel of an order has got to (V9
 * {@code order_fulfilment.status}).
 *
 * <p>Deliberately NOT extra values on {@code OrderStatus}. That enum answers
 * "has this been paid for": {@code PENDING_PAYMENT} is what payment-service
 * reads to decide an order is payable, and {@code PAID} is what the
 * verified-purchase review gate queries — a delivered order that had moved on
 * from PAID would silently stop being reviewable. Payment state and fulfilment
 * state are different questions about the same order and are stored as such.
 *
 * <p>The vocabulary is shared by both delivery methods rather than forked. A
 * collection order's {@code DISPATCHED} means "ready at the counter" and its
 * {@code DELIVERED} means "collected"; the app already knows the order's
 * {@code deliveryMethod} and labels accordingly. Two parallel vocabularies
 * would double every state machine, query and dashboard for a wording
 * difference.
 */
@Schema(description = "Where one seller's parcel of an order has got to")
public enum FulfilmentStatus {

    /** Opened when the order is PAID: the seller owes the buyer these goods. */
    PREPARING,

    /** Sent (DELIVERY) or ready at the counter (COLLECTION). */
    DISPATCHED,

    /** Handed over. Terminal — either the buyer confirmed receipt or the seller
     *  marked it delivered. */
    DELIVERED
}
