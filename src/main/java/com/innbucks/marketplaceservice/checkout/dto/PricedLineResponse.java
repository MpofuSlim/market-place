package com.innbucks.marketplaceservice.checkout.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.innbucks.marketplaceservice.catalog.dto.ListingResponse;
import com.innbucks.marketplaceservice.catalog.dto.ListingVariantResponse;
import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * One basket line resolved against the LIVE catalogue — shared by the cart
 * read and the checkout quote so the two screens render from one component and
 * cannot drift apart.
 *
 * <p>{@code listing} is the full catalogue summary (images, seller badge,
 * category, rating, CURRENT price and stock), so neither screen re-fetches per
 * row. It is null only when the listing can no longer be bought at all, in
 * which case {@code issue} says so and {@code listingId} is what the client
 * removes.
 *
 * <p>Prices here are today's, never what they were when the item was added — a
 * cart makes no promise, and quoting a stale price would be one. The price is
 * frozen at ORDER time, where the buyer has agreed to it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One basket line with its live price and availability")
public record PricedLineResponse(

        @Schema(example = "b4c2f0a8-3d1e-4e5a-9c7b-2f8d6a1e4b93")
        UUID listingId,

        @Schema(description = "The listing as the catalogue shows it right now. Null when the "
                + "listing is no longer buyable — see `issue`.", nullable = true)
        ListingResponse listing,

        @Schema(description = "Units on this line", example = "2")
        int quantity,

        @Schema(description = "quantity x the line's CURRENT unit price, in minor units. 0 for a "
                + "line that cannot be bought — an unbuyable line never contributes to a total.",
                example = "4798")
        long lineTotalCents,

        @Schema(description = "Why this line would be refused at checkout, or absent when it is "
                + "fine. Same vocabulary as an order refusal, so the app renders one component "
                + "for all three surfaces.", nullable = true)
        OrderLineRejection issue,

        @Schema(description = "When it was added to the cart — the cart's ordering key. Absent on "
                + "a quote built from explicit items rather than from the cart.",
                example = "2026-09-14T11:02:44Z", nullable = true)
        Instant addedAt,

        @Schema(description = "The option this line is for (V19), echoed from the cart or the "
                + "request - present even when the option no longer exists, so the app can still "
                + "remove the line with DELETE ...?variantId=. Absent for a listing without options.",
                example = "0a6f2d18-5c3b-4e97-8d21-b4f7e9c1a352", nullable = true)
        UUID variantId,

        @Schema(description = "The option as it is right now (label, price, stock). Absent when "
                + "the line names none, or the option was removed - see `issue`.", nullable = true)
        ListingVariantResponse variant,

        @Schema(description = "What ONE unit of this line costs right now, in minor units - the "
                + "option's price when the line names one. Use this, not listing.priceCents, "
                + "which is the listing's lowest (\"from\") price. Absent when the listing could "
                + "not be read.", example = "1999", nullable = true)
        Long unitPriceCents) {

    /** The pre-V19 shape: no option, no explicit unit price. */
    public PricedLineResponse(UUID listingId, ListingResponse listing, int quantity,
                              long lineTotalCents, OrderLineRejection issue, Instant addedAt) {
        this(listingId, listing, quantity, lineTotalCents, issue, addedAt, null, null, null);
    }
}
