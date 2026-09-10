package com.innbucks.marketplaceservice.order.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * One line of a rejected order, and why.
 *
 * <p><b>Why this exists.</b> Order creation used to abort at the FIRST bad
 * line, so a cart with two problems cost the customer two round-trips and two
 * separate corrections — and the second problem only appeared after they had
 * fixed the first. Creation still refuses the order as a whole (a partial
 * order is never minted), but it now names EVERY line that failed, so the app
 * can show one complete correction before the customer commits to paying.
 *
 * <p>{@code availableQty} and {@code unitPriceCents} carry the current truth
 * for that listing where it is known, so the correction needs no second fetch.
 * Both are null when they would be meaningless — a listing that no longer
 * exists has neither.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "OrderLineRejection", description = "One order line that could not be accepted, and why.")
public record OrderLineRejection(


        @Schema(description = "The line's listing id, exactly as it was sent",
                example = "9c2e8a4d-6b1f-4e3a-9d5c-7f8e2a1b3c4d")
        UUID listingId,

        @Schema(description = """
                Stable machine-readable reason. Branch on this, never on the message.
                * `LISTING_UNAVAILABLE` — the listing no longer exists, is no longer on sale, \
                or is priced in another currency. The remedy is the same for all three: drop the line.
                * `INSUFFICIENT_STOCK` — fewer units remain than were asked for; `availableQty` \
                says how many. Zero means sold out.
                A client that does not recognise a reason should show the message and drop the line.""",
                example = "INSUFFICIENT_STOCK",
                allowableValues = {"LISTING_UNAVAILABLE", "INSUFFICIENT_STOCK"})
        String reason,

        @Schema(description = "Human-readable explanation, safe to show a customer.",
                example = "Only 3 left of Solar Lantern 20W")
        String message,

        @Schema(description = "Units the buyer asked for on this line.", example = "5")
        Integer requestedQty,

        @Schema(description = "Units actually available right now. Present only for "
                + "`INSUFFICIENT_STOCK`; 0 means sold out.", example = "3", nullable = true)
        Integer availableQty,

        @Schema(description = "The listing's current unit price in MINOR units (cents), so the app "
                + "can show the corrected line without a second fetch. Null when the listing could "
                + "not be read at all.", example = "1550", nullable = true)
        Long unitPriceCents
) {

    /**
     * Reasons are STRINGS, not an enum, for the same reason user-service's
     * notification types are: a client that meets an unrecognised one must
     * render it, not choke on it, and adding a reason here should never be a
     * breaking change. The two constants below are what the service emits
     * today.
     */
    public static final String REASON_UNAVAILABLE = "LISTING_UNAVAILABLE";
    public static final String REASON_INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";

    /** Missing, not ACTIVE, or priced in another currency — one remedy: drop the line. */
    public static OrderLineRejection unavailable(UUID listingId, int requestedQty) {
        return new OrderLineRejection(listingId, REASON_UNAVAILABLE,
                "Listing " + listingId + " is not available", requestedQty, null, null);
    }

    public static OrderLineRejection insufficientStock(UUID listingId, String title, int requestedQty,
                                                       int availableQty, long unitPriceCents) {
        // Named, because "only 3 left of Solar Lantern 20W" is something a
        // customer can act on and a bare listing id is not.
        String message = availableQty == 0
                ? title + " is sold out"
                : "Only " + availableQty + " left of " + title;
        return new OrderLineRejection(listingId, REASON_INSUFFICIENT_STOCK, message,
                requestedQty, availableQty, unitPriceCents);
    }
}
