package com.innbucks.marketplaceservice.checkout;

import java.util.UUID;

/**
 * The identity of a basket line (V19): the listing plus the option, null for a
 * listing without options. One listing in two sizes is two lines; the same
 * size twice is a duplicate.
 */
public record LineKey(UUID listingId, UUID variantId) {
}
