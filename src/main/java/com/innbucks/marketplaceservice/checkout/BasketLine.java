package com.innbucks.marketplaceservice.checkout;

import java.util.UUID;

/** A (listing, quantity) pair as the shopper asked for it — a cart row, a
 *  quote line, or an order line, before anything has been priced. */
public record BasketLine(UUID listingId, int quantity) {
}
