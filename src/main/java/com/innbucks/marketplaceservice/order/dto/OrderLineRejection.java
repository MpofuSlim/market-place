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
                * `NOT_DELIVERED_TO_TOWN` — the order is for DELIVERY and this listing's seller does \
                not deliver to the address's town. Remedy: choose COLLECTION, another address, or \
                drop the line.
                * `VARIANT_REQUIRED` — the listing sells options (sizes, colours) and the line named \
                none. Remedy: let the shopper pick one (the listing's `variants`).
                * `VARIANT_UNAVAILABLE` — the option named no longer exists, or is not an option of \
                this listing. Remedy: pick another option, or drop the line.
                A client that does not recognise a reason should show the message and drop the line.""",
                example = "INSUFFICIENT_STOCK",
                allowableValues = {"LISTING_UNAVAILABLE", "INSUFFICIENT_STOCK", "NOT_DELIVERED_TO_TOWN",
                        "VARIANT_REQUIRED", "VARIANT_UNAVAILABLE"})
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
                + "not be read at all. For an option, the OPTION's price.",
                example = "1550", nullable = true)
        Long unitPriceCents,

        @Schema(description = "The option the line named (V19), echoed so a basket with two sizes "
                + "of one listing can tell its lines apart. Absent for a listing without options.",
                example = "1b7e3e29-6d4c-4fa8-9e32-c5a8f0d2b463", nullable = true)
        UUID variantId,

        @Schema(description = "The option's label when it could be read (\"L - Black\"). "
                + "Absent without an option, or when the option no longer exists.",
                example = "L - Black", nullable = true)
        String variantLabel
) {

    /** A line without an option — the pre-V19 shape. */
    public OrderLineRejection(UUID listingId, String reason, String message, Integer requestedQty,
                              Integer availableQty, Long unitPriceCents) {
        this(listingId, reason, message, requestedQty, availableQty, unitPriceCents, null, null);
    }

    /**
     * Reasons are STRINGS, not an enum, for the same reason user-service's
     * notification types are: a client that meets an unrecognised one must
     * render it, not choke on it, and adding a reason here should never be a
     * breaking change. The two constants below are what the service emits
     * today.
     */
    public static final String REASON_UNAVAILABLE = "LISTING_UNAVAILABLE";
    public static final String REASON_INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    public static final String REASON_NOT_DELIVERED_TO_TOWN = "NOT_DELIVERED_TO_TOWN";
    public static final String REASON_VARIANT_REQUIRED = "VARIANT_REQUIRED";
    public static final String REASON_VARIANT_UNAVAILABLE = "VARIANT_UNAVAILABLE";

    /** Missing, not ACTIVE, or priced in another currency — one remedy: drop the line. */
    public static OrderLineRejection unavailable(UUID listingId, int requestedQty) {
        return new OrderLineRejection(listingId, REASON_UNAVAILABLE,
                "Listing " + listingId + " is not available", requestedQty, null, null);
    }

    /** A DELIVERY order to a town this listing's seller does not deliver to.
     *  Carries the price so the app can keep the line on screen while the
     *  shopper picks collection or another address. */
    public static OrderLineRejection notDeliveredToTown(UUID listingId, String title,
                                                        int requestedQty, long unitPriceCents,
                                                        String townName) {
        return new OrderLineRejection(listingId, REASON_NOT_DELIVERED_TO_TOWN,
                title + " is not delivered to " + townName, requestedQty, null, unitPriceCents);
    }

    public static OrderLineRejection insufficientStock(UUID listingId, String title, int requestedQty,
                                                       int availableQty, long unitPriceCents) {
        return insufficientStock(listingId, title, requestedQty, availableQty, unitPriceCents,
                null, null);
    }

    /** V19: an under-stocked OPTION, named with its label ("Only 3 left of
     *  Cotton Crew Tee (M - Black)"); without an option, exactly the pre-V19
     *  line. */
    public static OrderLineRejection insufficientStock(UUID listingId, String title, int requestedQty,
                                                       int availableQty, long unitPriceCents,
                                                       UUID variantId, String variantLabel) {
        // Named, because "only 3 left of Solar Lantern 20W" is something a
        // customer can act on and a bare listing id is not.
        String name = variantLabel == null ? title : title + " (" + variantLabel + ")";
        String message = availableQty == 0
                ? name + " is sold out"
                : "Only " + availableQty + " left of " + name;
        return new OrderLineRejection(listingId, REASON_INSUFFICIENT_STOCK, message,
                requestedQty, availableQty, unitPriceCents, variantId, variantLabel);
    }

    /** {@link #unavailable} on a line that named an option — the message is
     *  unchanged, the option is echoed so the app can find the line. */
    public static OrderLineRejection unavailable(UUID listingId, int requestedQty, UUID variantId,
                                                 String variantLabel) {
        return new OrderLineRejection(listingId, REASON_UNAVAILABLE,
                "Listing " + listingId + " is not available", requestedQty, null, null,
                variantId, variantLabel);
    }

    /** {@link #notDeliveredToTown} on a line that named an option. */
    public static OrderLineRejection notDeliveredToTown(UUID listingId, String title,
                                                        int requestedQty, long unitPriceCents,
                                                        String townName, UUID variantId,
                                                        String variantLabel) {
        return new OrderLineRejection(listingId, REASON_NOT_DELIVERED_TO_TOWN,
                title + " is not delivered to " + townName, requestedQty, null, unitPriceCents,
                variantId, variantLabel);
    }

    /**
     * The listing sells options and the line named none (V19). {@code axes}
     * are the option names ("Size", "Colour"), so the message says what to
     * choose: "Choose a Size and Colour for Cotton Crew Tee".
     */
    public static OrderLineRejection variantRequired(UUID listingId, String title, String axes,
                                                     int requestedQty, long unitPriceCents) {
        return new OrderLineRejection(listingId, REASON_VARIANT_REQUIRED,
                "Choose a " + axes + " for " + title, requestedQty, null, unitPriceCents,
                null, null);
    }

    /** The option named is gone, or is not an option of this listing (V19). */
    public static OrderLineRejection variantUnavailable(UUID listingId, String title,
                                                        UUID variantId, int requestedQty,
                                                        long unitPriceCents) {
        return new OrderLineRejection(listingId, REASON_VARIANT_UNAVAILABLE,
                "The option you chose for " + title + " is no longer available - choose another",
                requestedQty, null, unitPriceCents, variantId, null);
    }
}
