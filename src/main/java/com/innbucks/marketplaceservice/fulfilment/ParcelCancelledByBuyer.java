package com.innbucks.marketplaceservice.fulfilment;

import java.util.UUID;

/** The buyer cancelled a paid parcel before it was dispatched. Handled after
 *  commit: the seller is told not to send it. The buyer is told nothing — they
 *  did it themselves, on the screen in front of them. */
public record ParcelCancelledByBuyer(UUID merchantId, String orderRef, UUID fulfilmentId,
                                     String buyerReason) {
}
