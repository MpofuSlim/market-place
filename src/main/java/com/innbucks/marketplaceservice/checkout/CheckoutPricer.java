package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingDeliveryTown;
import com.innbucks.marketplaceservice.catalog.ListingDeliveryTownRepository;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.catalog.variant.ListingVariant;
import com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.delivery.DeliveryTownCatalog;
import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * THE one definition of "can this be bought, and what does it cost right now".
 *
 * <p>The cart read, the checkout quote and order creation all resolve their
 * lines through here, so the three can never disagree about whether something
 * is available — which matters because they are the three screens a shopper
 * sees in a row. A cart that says "in stock", a quote that prices it and an
 * order that then refuses it is the worst possible sequence, and it is exactly
 * what independent copies of this rule produce as they drift.
 *
 * <p>The issue vocabulary is {@link OrderLineRejection}'s, deliberately reused
 * rather than re-invented per surface: a client learns
 * {@code LISTING_UNAVAILABLE} / {@code INSUFFICIENT_STOCK} once and renders it
 * everywhere.
 *
 * <p><b>Delivery is part of "can this be bought"</b> (V14). A DELIVERY basket
 * is priced against the buyer's town: a line whose seller does not deliver
 * there is an issue ({@code NOT_DELIVERED_TO_TOWN}) exactly like a sold-out
 * one, and each SELLER's delivery fee is the highest of their lines' fees to
 * that town — one parcel per seller makes one trip, so three items from the
 * same shop are not three delivery charges. The quote and the order both
 * price through here, so the fee a buyer is quoted is the fee they are
 * charged.
 *
 * <p><b>Advisory, always.</b> Stock read here can be stale the instant it is
 * taken. {@code ListingStock.reserveAll}'s guarded UPDATEs (the listing's, or
 * each option's) remain the authoritative guard at order creation — this exists so the COMMON case
 * reports every problem at once, on the screen before the customer commits,
 * instead of one problem per attempt.
 */
@Component
public class CheckoutPricer {

    private final ListingRepository listingRepository;
    private final ListingDeliveryTownRepository deliveryTownRepository;
    private final DeliveryTownCatalog towns;
    private final String currency;
    private final ListingVariantRepository variantRepository;

    public CheckoutPricer(ListingRepository listingRepository,
                          ListingDeliveryTownRepository deliveryTownRepository,
                          DeliveryTownCatalog towns,
                          @Value("${innbucks.currency}") String currency,
                          ListingVariantRepository variantRepository) {
        this.listingRepository = listingRepository;
        this.deliveryTownRepository = deliveryTownRepository;
        this.towns = towns;
        this.currency = currency;
        this.variantRepository = variantRepository;
    }

    /**
     * Prices a basket, preserving REQUEST order. Loads every listing in ONE
     * query regardless of basket size.
     *
     * @throws ApiException 422 {@code order_total_overflow} if the sellable
     *         lines cannot be summed — checked here rather than at each caller
     *         so no surface can present a total it cannot charge.
     */
    @Transactional(readOnly = true)
    public PricedBasket price(List<BasketLine> requested) {
        return price(requested, null, null);
    }

    /**
     * {@link #price(List)} for a checkout that has chosen how the goods arrive.
     * For {@code DELIVERY}, {@code townCode} is the destination's town: every
     * line must be delivered there, and the delivery fee is summed per seller.
     * Any other method prices goods only.
     */
    @Transactional(readOnly = true)
    public PricedBasket price(List<BasketLine> requested, DeliveryMethod method, String townCode) {
        if (requested.isEmpty()) {
            return new PricedBasket(List.of(), 0L, List.of());
        }
        List<UUID> listingIds = requested.stream().map(BasketLine::listingId).distinct().toList();
        Map<UUID, Listing> listings = listingRepository
                .findAllById(listingIds)
                .stream()
                .collect(Collectors.toMap(Listing::getId, l -> l));
        // V19: every named option in ONE query, and none at all for a basket
        // of listings without options.
        List<UUID> variantIds = requested.stream().map(BasketLine::variantId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, ListingVariant> variants = variantIds.isEmpty()
                ? Map.of()
                : variantRepository.findAllById(variantIds).stream()
                        .collect(Collectors.toMap(ListingVariant::getId, v -> v));
        boolean delivering = method == DeliveryMethod.DELIVERY;
        // One query for every line's coverage — never one per line.
        Map<UUID, Map<String, Long>> coverage = delivering ? coverageOf(listingIds) : Map.of();
        String townName = delivering ? towns.nameOf(townCode) : null;
        Map<UUID, Long> feeByMerchant = new LinkedHashMap<>();

        List<PricedLine> lines = new ArrayList<>(requested.size());
        List<OrderLineRejection> issues = new ArrayList<>(0);
        long subtotal = 0L;
        for (BasketLine line : requested) {
            Listing listing = listings.get(line.listingId());
            // Only an option OF THIS listing counts as the line's option; a
            // foreign or missing one is classified below and priced as nothing.
            // (An immutable Map refuses a null key, so a line without an option
            // must not ask.)
            ListingVariant variant = line.variantId() == null ? null : variants.get(line.variantId());
            if (variant != null && (listing == null || !listing.isHasVariants()
                    || !variant.getListingId().equals(listing.getId()))) {
                variant = null;
            }
            OrderLineRejection issue = classify(listing, line, variant);
            if (issue == null && delivering) {
                Long fee = coverage.getOrDefault(listing.getId(), Map.of()).get(townCode);
                if (fee == null) {
                    issue = OrderLineRejection.notDeliveredToTown(listing.getId(), listing.getTitle(),
                            line.quantity(),
                            variant == null ? listing.getPriceCents()
                                    : variant.effectivePriceCents(listing.getPriceCents()),
                            townName == null ? "this town" : townName,
                            line.variantId(), variant == null ? null : variant.label());
                } else {
                    // One parcel per seller: the trip costs the dearest of
                    // that seller's lines to this town, not their sum.
                    feeByMerchant.merge(listing.getMerchantId(), fee, Math::max);
                }
            }
            // An unavailable listing is handed on as null even when a row was
            // found: nothing downstream should price, name or attribute a
            // listing this surface has just declared unbuyable.
            // A VARIANT_* issue keeps its listing: the seller is still known
            // (V18's collection-point resolution needs them) and the line can
            // still be named and priced for the shopper who must fix it.
            boolean unavailable = issue != null
                    && OrderLineRejection.REASON_UNAVAILABLE.equals(issue.reason());
            PricedLine priced = new PricedLine(line.listingId(), line.quantity(),
                    unavailable ? null : listing,
                    issue, line.variantId(), unavailable ? null : variant);
            lines.add(priced);
            if (issue != null) {
                issues.add(issue);
            } else {
                try {
                    // The line's own unit price, so a line and the subtotal it
                    // feeds can never disagree about what an option costs.
                    subtotal = Math.addExact(subtotal,
                            Math.multiplyExact(priced.unitPriceCents(), (long) line.quantity()));
                } catch (ArithmeticException ex) {
                    throw ApiException.unprocessable("order_total_overflow",
                            "Order total exceeds the maximum representable amount");
                }
            }
        }
        long deliveryFee = 0L;
        try {
            for (long fee : feeByMerchant.values()) {
                deliveryFee = Math.addExact(deliveryFee, fee);
            }
        } catch (ArithmeticException ex) {
            throw ApiException.unprocessable("order_total_overflow",
                    "Order total exceeds the maximum representable amount");
        }
        return new PricedBasket(List.copyOf(lines), subtotal, List.copyOf(issues), deliveryFee,
                java.util.Collections.unmodifiableMap(feeByMerchant));
    }

    private Map<UUID, Map<String, Long>> coverageOf(List<UUID> listingIds) {
        Map<UUID, Map<String, Long>> byListing = new HashMap<>();
        for (ListingDeliveryTown row : deliveryTownRepository.findByListingIdIn(listingIds)) {
            byListing.computeIfAbsent(row.getListingId(), id -> new HashMap<>())
                    .put(row.getTownCode(), row.getFeeCents());
        }
        return byListing;
    }

    /**
     * One reason for missing / not ACTIVE / foreign currency: the client remedy
     * is identical (drop the line), and it leaks nothing the public catalog
     * does not already show.
     *
     * <p>V19, after availability and before stock: a listing that sells
     * options needs one named ({@code VARIANT_REQUIRED}), and a named option
     * must be one of THIS listing's ({@code VARIANT_UNAVAILABLE} — also for an
     * option named on a listing without options). Stock is then the OPTION's.
     *
     * @param variant the line's option, already confirmed to belong to
     *                {@code listing}; null when none was named or it is not
     *                this listing's
     */
    private OrderLineRejection classify(Listing listing, BasketLine line, ListingVariant variant) {
        if (listing == null
                || listing.getStatus() != ListingStatus.ACTIVE
                || !currency.equals(listing.getCurrency())) {
            return line.variantId() == null
                    ? OrderLineRejection.unavailable(line.listingId(), line.quantity())
                    : OrderLineRejection.unavailable(line.listingId(), line.quantity(), line.variantId(),
                            variant == null ? null : variant.label());
        }
        if (listing.isHasVariants() && line.variantId() == null) {
            return OrderLineRejection.variantRequired(listing.getId(), listing.getTitle(),
                    axes(listing), line.quantity(), listing.getPriceCents());
        }
        if (line.variantId() != null && variant == null) {
            return OrderLineRejection.variantUnavailable(listing.getId(), listing.getTitle(),
                    line.variantId(), line.quantity(), listing.getPriceCents());
        }
        if (variant != null) {
            if (variant.getStockQty() < line.quantity()) {
                return OrderLineRejection.insufficientStock(line.listingId(), listing.getTitle(),
                        line.quantity(), variant.getStockQty(),
                        variant.effectivePriceCents(listing.getPriceCents()),
                        variant.getId(), variant.label());
            }
            return null;
        }
        if (listing.getStockQty() < line.quantity()) {
            return OrderLineRejection.insufficientStock(line.listingId(), listing.getTitle(),
                    line.quantity(), listing.getStockQty(), listing.getPriceCents());
        }
        return null;
    }

    /** "Size" or "Size and Colour" — what the shopper has to choose. */
    private static String axes(Listing listing) {
        String first = listing.getOption1Name() == null ? "option" : listing.getOption1Name();
        return listing.getOption2Name() == null ? first : first + " and " + listing.getOption2Name();
    }
}
