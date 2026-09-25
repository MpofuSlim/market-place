package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.variant.ListingVariant;
import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;

import java.util.UUID;

/**
 * One basket line resolved against the LIVE listing row: what it costs right
 * now, who sells it, and what (if anything) is wrong with it.
 *
 * <p>{@code issue} is null for a line that can be bought as asked. When it is
 * set, {@code listing} may still be present — an under-stocked line knows its
 * title and price, an unavailable one may not — so every field below
 * {@code quantity} is best-effort and callers must not assume it.
 *
 * <p>V19: {@code variantId} is the option the shopper asked for (null on a
 * listing without options) and {@code variant} the live option row, null when
 * none was asked for or the option no longer exists. The price is the
 * OPTION's when there is one ({@link ListingVariant#effectivePriceCents}),
 * computed here once so a line and the subtotal it feeds cannot disagree.
 */
public record PricedLine(UUID listingId,
                         int quantity,
                         Listing listing,
                         OrderLineRejection issue,
                         UUID variantId,
                         ListingVariant variant) {

    /** A line on a listing without options — the pre-V19 shape. */
    public PricedLine(UUID listingId, int quantity, Listing listing, OrderLineRejection issue) {
        this(listingId, quantity, listing, issue, null, null);
    }

    /** True when this line can be bought exactly as asked. */
    public boolean sellable() {
        return issue == null;
    }

    public long unitPriceCents() {
        if (listing == null) {
            return 0L;
        }
        return variant != null
                ? variant.effectivePriceCents(listing.getPriceCents())
                : listing.getPriceCents();
    }

    /** {@code unitPrice * quantity}, or 0 for a line that cannot be bought —
     *  an unsellable line must never contribute to a total. */
    public long lineTotalCents() {
        return sellable() ? unitPriceCents() * quantity : 0L;
    }

    /** The option's label ("M - Black"), or null without one. */
    public String variantLabel() {
        return variant == null ? null : variant.label();
    }

    public LineKey key() {
        return new LineKey(listingId, variantId);
    }
}
