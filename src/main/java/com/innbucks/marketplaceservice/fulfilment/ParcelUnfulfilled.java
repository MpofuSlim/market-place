package com.innbucks.marketplaceservice.fulfilment;

import java.util.UUID;

/**
 * In-process domain event: a seller declared they cannot supply one parcel
 * (V12). Published inside the declining transaction and consumed
 * {@code AFTER_COMMIT}, so a buyer is never told their goods are not coming
 * because of a transaction that then rolled back.
 *
 * <p>Carries what the message needs so the listener composes without
 * re-reading the order — the same shape as {@code OrderPaid}.
 */
public record ParcelUnfulfilled(UUID orderId,
                                String orderRef,
                                String buyerMsisdn,
                                String sellerReason,
                                long refundDueCents,
                                String currency) {

    /** Whether the platform actually queued money back. A parcel whose money
     *  was already disputed or paid out turns nothing around here, and the
     *  copy must not promise a refund in that case. */
    public boolean refundQueued() {
        return refundDueCents > 0;
    }
}
