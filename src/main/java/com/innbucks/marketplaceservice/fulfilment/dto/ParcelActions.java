package com.innbucks.marketplaceservice.fulfilment.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the buyer may do with this parcel right now — computed by the server
 * from the same rules its endpoints enforce ({@code BuyerParcelRules}), so the
 * app shows exactly the actions that will be accepted and learns about a rule
 * change without an app update.
 *
 * <p>Advance notice, not a guarantee: if the seller acts in between (dispatches
 * the parcel, say), the endpoint answers with its usual 409 — re-render from
 * the response it returns.
 */
@Schema(description = "What the buyer may do with this parcel right now, decided by the "
        + "server's own rules. Show exactly these actions; a rule change reaches the app without "
        + "an update. A flag can go stale if the seller acts in between - the action then returns "
        + "its usual 409, and the response it returns is the fresh state.")
public record ParcelActions(

        @Schema(description = "\"I have received it\" - POST .../fulfilments/{id}/received. True "
                + "while the parcel is open (PREPARING or DISPATCHED). Releases the seller's "
                + "payment at once - unless it is under dispute, when support decides.",
                example = "true")
        boolean canConfirmReceipt,

        @Schema(description = "Show a collection code - POST .../fulfilments/{id}/collect-code. "
                + "COLLECTION parcels that are still open.", example = "false")
        boolean canRequestCollectCode,

        @Schema(description = "Cancel this parcel - POST .../fulfilments/{id}/cancel. Only while "
                + "the seller is still preparing it and the payment is still held.",
                example = "false")
        boolean canCancel,

        @Schema(description = "Report a problem - POST .../fulfilments/{id}/dispute. Once per "
                + "parcel; until `disputableUntil` when the parcel has been delivered.",
                example = "true")
        boolean canDispute) {

    /** Nothing is possible — a closed parcel with no dispute left to raise. */
    public static final ParcelActions NONE = new ParcelActions(false, false, false, false);
}
