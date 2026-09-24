package com.innbucks.marketplaceservice.fulfilment.notice;

import io.swagger.v3.oas.annotations.media.Schema;

/** Which message a seller's action sent the buyer about a parcel. */
@Schema(description = "What the buyer was told: DISPATCHED (on its way), READY_TO_COLLECT, "
        + "DELIVERED_BY_SELLER (you marked it delivered), CANCELLED (declined or not collected)")
public enum BuyerNoticeKind {
    DISPATCHED,
    READY_TO_COLLECT,
    DELIVERED_BY_SELLER,
    CANCELLED
}
