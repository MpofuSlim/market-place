package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.catalog.Listing;
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
 */
public record PricedLine(UUID listingId,
                         int quantity,
                         Listing listing,
                         OrderLineRejection issue) {

    /** True when this line can be bought exactly as asked. */
    public boolean sellable() {
        return issue == null;
    }

    public long unitPriceCents() {
        return listing == null ? 0L : listing.getPriceCents();
    }

    /** {@code unitPrice * quantity}, or 0 for a line that cannot be bought —
     *  an unsellable line must never contribute to a total. */
    public long lineTotalCents() {
        return sellable() ? unitPriceCents() * quantity : 0L;
    }
}
