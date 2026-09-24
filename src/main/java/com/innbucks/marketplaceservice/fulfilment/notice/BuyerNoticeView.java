package com.innbucks.marketplaceservice.fulfilment.notice;

import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** The last message your action sent the buyer about this parcel, and whether it arrived. */
@Schema(description = "The last message your action sent the buyer, and whether it went out")
public record BuyerNoticeView(

        @Schema(example = "DISPATCHED")
        BuyerNoticeKind kind,

        @Schema(example = "FAILED")
        BuyerNoticeOutcome outcome,

        @Schema(example = "2026-09-24T09:20:04Z")
        Instant at) {

    /** Null until a seller action has sent (or tried to send) something. */
    public static BuyerNoticeView of(OrderFulfilment parcel) {
        if (parcel.getBuyerNoticeKind() == null || parcel.getBuyerNoticeOutcome() == null) {
            return null;
        }
        try {
            return new BuyerNoticeView(BuyerNoticeKind.valueOf(parcel.getBuyerNoticeKind()),
                    BuyerNoticeOutcome.valueOf(parcel.getBuyerNoticeOutcome()),
                    parcel.getBuyerNoticeAt());
        } catch (IllegalArgumentException ex) {
            // A value written by a newer build: better absent than a 500.
            return null;
        }
    }
}
