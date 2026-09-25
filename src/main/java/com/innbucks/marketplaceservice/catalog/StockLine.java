package com.innbucks.marketplaceservice.catalog;

import java.util.UUID;

/**
 * One stock movement's worth of a line: which listing, which option (null for
 * a listing without variants) and how many units. The only shape
 * {@link ListingStock} moves stock by, whether the line came from a priced
 * basket (reserve) or an order's items (return).
 */
public record StockLine(UUID listingId, UUID variantId, int quantity) {
}
