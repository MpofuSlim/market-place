package com.innbucks.marketplaceservice.checkout;

import java.util.UUID;

/**
 * A (listing, option, quantity) line as the shopper asked for it — a cart row,
 * a quote line, or an order line, before anything has been priced.
 * {@code variantId} is null for a listing without options (V19), which is
 * exactly what every line meant before options existed.
 */
public record BasketLine(UUID listingId, int quantity, UUID variantId) {

    /** A line on a listing without options — the pre-V19 shape. */
    public BasketLine(UUID listingId, int quantity) {
        this(listingId, quantity, null);
    }

    /** What makes two lines "the same line": the listing and the option. */
    public LineKey key() {
        return new LineKey(listingId, variantId);
    }
}
