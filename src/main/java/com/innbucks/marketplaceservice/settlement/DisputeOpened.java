package com.innbucks.marketplaceservice.settlement;

import java.util.UUID;

/** A buyer disputed a parcel; its money is now frozen. Published inside the
 *  opening transaction and handled after commit, so a rolled-back open tells
 *  the seller nothing. */
public record DisputeOpened(UUID merchantId, String orderRef, UUID fulfilmentId,
                            DisputeReason reason) {
}
