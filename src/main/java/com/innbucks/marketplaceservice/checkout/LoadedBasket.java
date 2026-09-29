package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.variant.ListingVariant;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A basket with its listings and options read, before any delivery question is
 * asked (V20: load, then plan, then price). Loading once and pricing from this
 * is what lets a checkout decide its delivery plan against the sellers that
 * are actually on sale without a second listing query.
 *
 * @param requested the lines, in REQUEST order
 * @param listings  every listing the basket names that exists, by id
 * @param variants  every option the basket names that exists, by id (empty for
 *                  a basket naming none)
 * @param sellers   the distinct sellers of the ON-SALE lines, in basket order -
 *                  a line whose listing is missing, off sale or in another
 *                  currency names nobody, because it cannot be bought from
 *                  anyone
 */
public record LoadedBasket(List<BasketLine> requested,
                           Map<UUID, Listing> listings,
                           Map<UUID, ListingVariant> variants,
                           List<UUID> sellers) {

    static final LoadedBasket EMPTY = new LoadedBasket(List.of(), Map.of(), Map.of(), List.of());

    public LoadedBasket {
        requested = List.copyOf(requested);
        listings = Map.copyOf(listings);
        variants = Map.copyOf(variants);
        sellers = List.copyOf(sellers);
    }
}
