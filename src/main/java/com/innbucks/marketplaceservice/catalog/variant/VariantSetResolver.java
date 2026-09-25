package com.innbucks.marketplaceservice.catalog.variant;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.dto.VariantRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns an editor's {@code options} + {@code variants} into a plan, refusing
 * everything a seller can get wrong BEFORE anything is written (V19). Pure: no
 * Spring, no repository — the existing options are handed in.
 *
 * <h2>Identity on replace</h2>
 * An entry with an {@code id} keeps that existing option (400
 * {@code unknown_variant} when it is not one of this listing's). An entry
 * without one keeps the existing, unclaimed option with the same values
 * (case-insensitive), else it is NEW. Existing options no entry keeps are
 * removed. A kept entry may omit {@code stockQty} to leave the option's stock
 * — and the reservations riding on it — alone; a given one is an absolute set.
 *
 * <h2>The floor rule</h2>
 * The listing's {@code priceCents} is the LOWEST option price. An option's own
 * price is a surcharge: below the listing price is refused
 * ({@code variant_price_below_listing_price}); equal is stored as "no own
 * price" so it follows the listing price afterwards; and at least one option
 * must sell at the listing price ({@code listing_price_not_offered}). Together
 * these keep {@code listing.price_cents == MIN(option price)} true, so browse
 * sort, the price window and every card keep reading one column.
 */
public final class VariantSetResolver {

    public static final int MAX_AXES = 2;
    public static final int MAX_NAME_LENGTH = 30;
    public static final int MAX_VALUE_LENGTH = 40;
    public static final long MIN_PRICE_CENTS = 1;
    public static final long MAX_PRICE_CENTS = 100_000_000;
    public static final int MAX_STOCK_QTY = 1_000_000;

    private VariantSetResolver() {
    }

    /**
     * One option as it will be after the write.
     *
     * @param existing      the kept row, or null for a new option
     * @param override      its own price, or null to sell at the listing price
     * @param stockQty      the absolute stock to set, or null to keep the
     *                      existing row's (never null for a new option)
     */
    public record Draft(ListingVariant existing, String value1, String value2, Long override,
                        Integer stockQty, int position) {

        public boolean isNew() {
            return existing == null;
        }

        /** The stock this option will hold. */
        public int effectiveStock() {
            return stockQty != null ? stockQty : existing.getStockQty();
        }
    }

    /**
     * @param optionNames the axes, normalised, in order
     * @param drafts      every option after the write, in the seller's order
     * @param removed     existing options no entry kept
     */
    public record VariantPlan(List<String> optionNames, List<Draft> drafts,
                              List<ListingVariant> removed) {

        public int totalStock() {
            return drafts.stream().mapToInt(Draft::effectiveStock).sum();
        }
    }

    /**
     * Resolves a NON-EMPTY {@code variants} list against the listing's
     * {@code existing} options (empty on create or for a listing without
     * options).
     *
     * @throws ApiException 400 with the codes in the class javadoc
     */
    public static VariantPlan resolve(List<String> options, List<VariantRequest> variants,
                                      long listingPriceCents, List<ListingVariant> existing,
                                      int maxVariants) {
        List<String> names = resolveNames(options);
        if (variants.size() > maxVariants) {
            throw ApiException.badRequest("too_many_variants",
                    "A listing can have at most " + maxVariants + " variants");
        }
        // Values first, for every entry, so every refusal below can name the
        // option in the seller's own words.
        List<String[]> values = new ArrayList<>(variants.size());
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < variants.size(); i++) {
            VariantRequest entry = variants.get(i);
            if (entry == null) {
                throw ApiException.badRequest("invalid_variant_option",
                        "variants[" + i + "] must not be empty");
            }
            List<String> raw = entry.values();
            if (raw == null || raw.size() != names.size()) {
                throw ApiException.badRequest("variant_values_mismatch",
                        "variants[" + i + "].values must give one value for each of "
                                + String.join(", ", names));
            }
            String v1 = VariantLabels.normalize(raw.get(0), "variants[" + i + "].values[0]",
                    MAX_VALUE_LENGTH);
            String v2 = names.size() == 2
                    ? VariantLabels.normalize(raw.get(1), "variants[" + i + "].values[1]",
                            MAX_VALUE_LENGTH)
                    : null;
            if (!keys.add(VariantLabels.key(v1, v2))) {
                throw ApiException.badRequest("duplicate_variant",
                        "variants names " + VariantLabels.label(v1, v2) + " more than once");
            }
            values.add(new String[] {v1, v2});
        }

        // Identity: ids claim first, then values match what is left.
        Map<UUID, ListingVariant> byId = new LinkedHashMap<>();
        existing.forEach(row -> byId.put(row.getId(), row));
        ListingVariant[] kept = new ListingVariant[variants.size()];
        Set<UUID> claimed = new HashSet<>();
        for (int i = 0; i < variants.size(); i++) {
            UUID id = variants.get(i).id();
            if (id == null) {
                continue;
            }
            ListingVariant row = byId.get(id);
            if (row == null) {
                throw ApiException.badRequest("unknown_variant",
                        "variants[" + i + "].id is not a variant of this listing");
            }
            if (!claimed.add(id)) {
                throw ApiException.badRequest("duplicate_variant",
                        "variants[" + i + "].id names an option already kept by another entry");
            }
            kept[i] = row;
        }
        Map<String, ListingVariant> unclaimedByKey = new HashMap<>();
        for (ListingVariant row : existing) {
            if (!claimed.contains(row.getId())) {
                unclaimedByKey.put(row.getOptionKey(), row);
            }
        }
        for (int i = 0; i < variants.size(); i++) {
            if (kept[i] != null || variants.get(i).id() != null) {
                continue;
            }
            ListingVariant row = unclaimedByKey.remove(VariantLabels.key(values.get(i)[0],
                    values.get(i)[1]));
            if (row != null) {
                kept[i] = row;
                claimed.add(row.getId());
            }
        }

        List<Draft> drafts = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            VariantRequest entry = variants.get(i);
            String label = VariantLabels.label(values.get(i)[0], values.get(i)[1]);
            Long override = normaliseOverride(entry.priceCents(), listingPriceCents,
                    "variants[" + i + "]", label);
            Integer stock = entry.stockQty();
            if (stock != null && (stock < 0 || stock > MAX_STOCK_QTY)) {
                throw ApiException.badRequest("stock_out_of_range",
                        "variants[" + i + "].stockQty must be between 0 and " + MAX_STOCK_QTY);
            }
            if (stock == null && kept[i] == null) {
                throw ApiException.badRequest("variant_stock_required",
                        "variants[" + i + "].stockQty is required for a new option");
            }
            drafts.add(new Draft(kept[i], values.get(i)[0], values.get(i)[1], override, stock, i));
        }
        requireListingPriceOffered(drafts.stream().map(Draft::override).toList());
        long total = drafts.stream().mapToLong(Draft::effectiveStock).sum();
        if (total > MAX_STOCK_QTY) {
            throw ApiException.badRequest("stock_out_of_range",
                    "The options' stock adds up to more than " + MAX_STOCK_QTY);
        }
        List<ListingVariant> removed = existing.stream()
                .filter(row -> !claimed.contains(row.getId()))
                .toList();
        return new VariantPlan(names, List.copyOf(drafts), removed);
    }

    /**
     * The {@code variants: null} path on a listing with options: nothing about
     * the options changes, but a new {@code priceCents} must still satisfy the
     * floor rule against the options' own prices.
     *
     * @return the options whose own price now EQUALS the listing price and
     *         should be cleared (so they follow it from now on)
     */
    public static List<ListingVariant> revalidateKept(List<ListingVariant> existing,
                                                      long listingPriceCents) {
        List<ListingVariant> normalise = new ArrayList<>();
        List<Long> after = new ArrayList<>(existing.size());
        for (ListingVariant row : existing) {
            Long own = row.getPriceCents();
            if (own != null && own < listingPriceCents) {
                throw ApiException.badRequest("variant_price_below_listing_price",
                        "The option " + row.label() + " costs less than priceCents - make "
                                + "priceCents the lowest option price");
            }
            if (own != null && own == listingPriceCents) {
                normalise.add(row);
                after.add(null);
            } else {
                after.add(own);
            }
        }
        requireListingPriceOffered(after);
        return normalise;
    }

    /** The axes: 1..2 names, normalised, unique case-insensitively. */
    private static List<String> resolveNames(List<String> options) {
        if (options == null || options.isEmpty()) {
            throw ApiException.badRequest("variant_options_required",
                    "variants need options - name each option, for example Size");
        }
        if (options.size() > MAX_AXES) {
            throw ApiException.badRequest("too_many_variant_options",
                    "A listing can have at most " + MAX_AXES + " options");
        }
        List<String> names = new ArrayList<>(options.size());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < options.size(); i++) {
            String name = VariantLabels.normalize(options.get(i), "options[" + i + "]",
                    MAX_NAME_LENGTH);
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw ApiException.badRequest("duplicate_variant_option",
                        "options names " + name + " more than once");
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    private static Long normaliseOverride(Long priceCents, long listingPriceCents, String field,
                                          String label) {
        if (priceCents == null) {
            return null;
        }
        if (priceCents < MIN_PRICE_CENTS || priceCents > MAX_PRICE_CENTS) {
            throw ApiException.badRequest("price_out_of_range",
                    field + ".priceCents must be between " + MIN_PRICE_CENTS + " and "
                            + MAX_PRICE_CENTS);
        }
        if (priceCents < listingPriceCents) {
            throw ApiException.badRequest("variant_price_below_listing_price",
                    field + " (" + label + ") costs less than priceCents - make priceCents the "
                            + "lowest option price");
        }
        return priceCents == listingPriceCents ? null : priceCents;
    }

    private static void requireListingPriceOffered(List<Long> overrides) {
        if (!overrides.isEmpty() && overrides.stream().allMatch(o -> o != null)) {
            throw ApiException.badRequest("listing_price_not_offered",
                    "No option sells at priceCents - make priceCents the lowest option price");
        }
    }
}
