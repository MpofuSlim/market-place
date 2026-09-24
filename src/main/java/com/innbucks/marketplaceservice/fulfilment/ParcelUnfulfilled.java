package com.innbucks.marketplaceservice.fulfilment;

import java.util.UUID;

/**
 * In-process domain event: a seller declared they cannot supply one parcel
 * (V12), or closed a collection the buyer never came for. Published inside the
 * declining transaction and consumed {@code AFTER_COMMIT}, so a buyer is never
 * told their goods are not coming because of a transaction that then rolled
 * back.
 *
 * <p>Carries what the message needs so the listener composes without
 * re-reading the order — the same shape as {@code OrderPaid}.
 *
 * <p>{@code notCollected} tells the two apart, because they are different
 * things to say to a buyer: "the seller cannot supply this" versus "you never
 * picked this up". Telling someone who simply did not come that the seller ran
 * out of stock would be a small lie with the platform's name on it.
 */
public record ParcelUnfulfilled(UUID orderId,
                                String orderRef,
                                String buyerMsisdn,
                                String sellerReason,
                                long refundDueCents,
                                String currency,
                                boolean notCollected,
                                UUID fulfilmentId) {

    /** Without a parcel id: nothing records the outcome (V15) on a parcel. */
    public ParcelUnfulfilled(UUID orderId, String orderRef, String buyerMsisdn,
                             String sellerReason, long refundDueCents, String currency,
                             boolean notCollected) {
        this(orderId, orderRef, buyerMsisdn, sellerReason, refundDueCents, currency,
                notCollected, null);
    }

    /** Whether the platform actually queued money back. A parcel whose money
     *  was already disputed or paid out turns nothing around here, and the
     *  copy must not promise a refund in that case. */
    public boolean refundQueued() {
        return refundDueCents > 0;
    }
}
