package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;

import java.util.UUID;

/**
 * In-process domain event: the SELLER moved one parcel forward — dispatched it
 * (or set it aside at the counter), or closed it as delivered on their own
 * word. Published inside the moving transaction and consumed
 * {@code AFTER_COMMIT}, so a buyer is never told about a move that then rolled
 * back.
 *
 * <p>Exists because the buyer's protection against a false "delivered" is a
 * dispute, and a dispute has a window. A buyer who is never told the seller
 * closed their parcel cannot use a window they do not know is running. Closes
 * the BUYER or the collection code performs are deliberately NOT published:
 * the person who did it already knows.
 *
 * <p>{@code partOfOrder} is true when the order has more than one seller, so
 * the copy says "part of your order" only when it is literally true.
 */
public record ParcelProgressed(UUID orderId,
                               String orderRef,
                               String buyerMsisdn,
                               DeliveryMethod deliveryMethod,
                               FulfilmentStatus status,
                               boolean partOfOrder,
                               UUID fulfilmentId) {

    /** Without a parcel id: nothing records the outcome (V15) on a parcel. */
    public ParcelProgressed(UUID orderId, String orderRef, String buyerMsisdn,
                            DeliveryMethod deliveryMethod, FulfilmentStatus status,
                            boolean partOfOrder) {
        this(orderId, orderRef, buyerMsisdn, deliveryMethod, status, partOfOrder, null);
    }
}
