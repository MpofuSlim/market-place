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
import com.innbucks.marketplaceservice.seller.MarketplaceSellerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p><b>So is how each seller's goods arrive</b> (V20). A checkout is priced in
 * three steps - {@link #load}, a {@link DeliveryPlan} resolved against the
 * sellers actually on sale, then {@link #price(LoadedBasket, DeliveryPlan,
 * String)} - and a COLLECTION line from a seller who only delivers is an issue
 * ({@code COLLECTION_NOT_OFFERED}) exactly like a line nobody delivers to the
 * buyer's town. Each seller's {@link SellerPricing} says which methods could
 * reach the buyer at all, from the same rows, so the app can offer only those.
 * The cart asks none of this: it is priced with {@link DeliveryPlan#NONE} and
 * reads no coverage and no seller setting.
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
    private final MarketplaceSellerRepository sellerRepository;
    private final CheckoutProperties properties;

    public CheckoutPricer(ListingRepository listingRepository,
                          ListingDeliveryTownRepository deliveryTownRepository,
                          DeliveryTownCatalog towns,
                          @Value("${innbucks.currency}") String currency,
                          ListingVariantRepository variantRepository,
                          MarketplaceSellerRepository sellerRepository,
                          CheckoutProperties properties) {
        this.listingRepository = listingRepository;
        this.deliveryTownRepository = deliveryTownRepository;
        this.towns = towns;
        this.currency = currency;
        this.variantRepository = variantRepository;
        this.sellerRepository = sellerRepository;
        this.properties = properties;
    }

    /**
     * Prices a basket for the CART, preserving REQUEST order: goods only, with
     * no delivery question asked ({@link DeliveryPlan#NONE}). Reads the
     * listings in ONE query (plus one for options when a line names one) and
     * nothing else - no delivery coverage and no seller settings - so whether
     * the cart says {@code checkoutReady} can never depend on a delivery method
     * the shopper has not chosen yet.
     *
     * @throws ApiException 422 {@code order_total_overflow} if the sellable
     *         lines cannot be summed — checked here rather than at each caller
     *         so no surface can present a total it cannot charge.
     */
    @Transactional(readOnly = true)
    public PricedBasket price(List<BasketLine> requested) {
        return price(load(requested), DeliveryPlan.NONE, null);
    }

    /**
     * {@link #price(List)} for a checkout that has chosen ONE method for the
     * whole basket: a {@link DeliveryPlan#uniform} plan over the methods this
     * cell offers. For {@code DELIVERY}, {@code townCode} is the destination's
     * town. A null method is the cart's plan.
     */
    @Transactional(readOnly = true)
    public PricedBasket price(List<BasketLine> requested, DeliveryMethod method, String townCode) {
        DeliveryPlan plan = method == null ? DeliveryPlan.NONE
                : DeliveryPlan.uniform(method, properties.getDelivery().getMethods());
        return price(load(requested), plan, townCode);
    }

    /**
     * Step one of a checkout: read every listing the basket names in ONE query,
     * and every option it names in one more (none at all when no line names
     * one). Nothing is decided here; the result is what a delivery plan is
     * resolved against ({@link LoadedBasket#sellers()} - the sellers actually
     * on sale) before {@link #price(LoadedBasket, DeliveryPlan, String)} prices
     * it, so a checkout never reads its listings twice.
     */
    @Transactional(readOnly = true)
    public LoadedBasket load(List<BasketLine> requested) {
        if (requested.isEmpty()) {
            return LoadedBasket.EMPTY;
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
        List<UUID> sellers = requested.stream()
                .map(line -> listings.get(line.listingId()))
                .filter(this::onSale)
                .map(Listing::getMerchantId)
                .distinct()
                .toList();
        return new LoadedBasket(requested, listings, variants, sellers);
    }

    /**
     * Prices a loaded basket under a delivery plan, preserving REQUEST order.
     *
     * <p>Per line, {@link #classify} runs FIRST and unchanged, so an older
     * reason always wins on a line. Only a line it accepts is then asked about
     * its SELLER's method: a delivering seller must deliver to
     * {@code townCode} ({@code NOT_DELIVERED_TO_TOWN} otherwise, and their fee
     * is the dearest of their lines to that town, never the sum), and a
     * collecting seller must still offer collection
     * ({@code COLLECTION_NOT_OFFERED} for a delivery-only one, V20).
     *
     * <p>A checkout (any plan but {@link DeliveryPlan#NONE}) reads delivery
     * coverage for every on-sale listing in ONE query - for a COLLECTION basket
     * too, because each seller's {@code availableMethods} needs it - and the
     * delivery-only sellers among the on-sale ones in ONE more. The cart reads
     * neither.
     *
     * @param townCode the destination's town for a delivering seller; for a
     *                 basket nobody delivers, the town to judge each seller's
     *                 DELIVERY availability against, or null when none is
     *                 known (then "delivers somewhere" is enough)
     * @throws IllegalArgumentException when a seller on sale delivers and
     *         {@code townCode} is null - a caller that resolved no destination
     *         for a delivery is a programming error, not a shopper's
     * @throws ApiException 422 {@code order_total_overflow}, as for the cart
     */
    @Transactional(readOnly = true)
    public PricedBasket price(LoadedBasket basket, DeliveryPlan plan, String townCode) {
        if (basket.requested().isEmpty()) {
            return new PricedBasket(List.of(), 0L, List.of());
        }
        boolean checkout = !plan.isNone();
        if (checkout && townCode == null) {
            for (UUID seller : basket.sellers()) {
                if (plan.methodFor(seller) == DeliveryMethod.DELIVERY) {
                    throw new IllegalArgumentException(
                            "Seller " + seller + " delivers but no destination town was resolved");
                }
            }
        }
        List<UUID> onSaleListingIds = checkout ? onSaleListingIds(basket) : List.of();
        // One query for every on-sale line's coverage — never one per line.
        Map<UUID, Map<String, Long>> coverage = onSaleListingIds.isEmpty()
                ? Map.of() : coverageOf(onSaleListingIds);
        // One query for the basket's delivery-only sellers. It selects only
        // the sellers that do NOT collect, so a seller with no record reads as
        // collecting - which every seller was before V20.
        Set<UUID> deliveryOnly = checkout && !basket.sellers().isEmpty()
                ? sellerRepository.findCollectionDisabledAmong(basket.sellers())
                : Set.of();
        String townName = townCode == null ? null : towns.nameOf(townCode);
        Map<UUID, Long> feeByMerchant = new LinkedHashMap<>();

        List<PricedLine> lines = new ArrayList<>(basket.requested().size());
        List<OrderLineRejection> issues = new ArrayList<>(0);
        long subtotal = 0L;
        for (BasketLine line : basket.requested()) {
            Listing listing = basket.listings().get(line.listingId());
            // Only an option OF THIS listing counts as the line's option; a
            // foreign or missing one is classified below and priced as nothing.
            // (An immutable Map refuses a null key, so a line without an option
            // must not ask.)
            ListingVariant variant = line.variantId() == null ? null
                    : basket.variants().get(line.variantId());
            if (variant != null && (listing == null || !listing.isHasVariants()
                    || !variant.getListingId().equals(listing.getId()))) {
                variant = null;
            }
            OrderLineRejection issue = classify(listing, line, variant);
            if (issue == null && checkout) {
                issue = methodIssue(plan, listing, line, variant, coverage, deliveryOnly, townCode,
                        townName, feeByMerchant);
            }
            // An unavailable listing is handed on as null even when a row was
            // found: nothing downstream should price, name or attribute a
            // listing this surface has just declared unbuyable.
            // A VARIANT_* issue keeps its listing: the seller is still known
            // (V18's collection-point resolution needs them) and the line can
            // still be named and priced for the shopper who must fix it. So do
            // the two method issues, for the same reasons.
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
        Map<UUID, SellerPricing> sellers = checkout
                ? sellerPricing(basket, plan, coverage, deliveryOnly, townCode, feeByMerchant)
                : Map.of();
        return new PricedBasket(List.copyOf(lines), subtotal, List.copyOf(issues), deliveryFee,
                Collections.unmodifiableMap(feeByMerchant), sellers);
    }

    /**
     * The delivery-method issue for a line {@link #classify} accepted, or null.
     * A delivering seller's fee to the town is merged here (dearest line wins).
     */
    private static OrderLineRejection methodIssue(DeliveryPlan plan, Listing listing,
                                                  BasketLine line, ListingVariant variant,
                                                  Map<UUID, Map<String, Long>> coverage,
                                                  Set<UUID> deliveryOnly, String townCode,
                                                  String townName, Map<UUID, Long> feeByMerchant) {
        UUID merchantId = listing.getMerchantId();
        long unitPriceCents = variant == null ? listing.getPriceCents()
                : variant.effectivePriceCents(listing.getPriceCents());
        String variantLabel = variant == null ? null : variant.label();
        return switch (plan.methodFor(merchantId)) {
            case DELIVERY -> {
                Long fee = coverage.getOrDefault(listing.getId(), Map.of()).get(townCode);
                if (fee == null) {
                    yield OrderLineRejection.notDeliveredToTown(listing.getId(), listing.getTitle(),
                            line.quantity(), unitPriceCents,
                            townName == null ? "this town" : townName,
                            line.variantId(), variantLabel, merchantId);
                }
                // One parcel per seller: the trip costs the dearest of that
                // seller's lines to this town, not their sum.
                feeByMerchant.merge(merchantId, fee, Math::max);
                yield null;
            }
            case COLLECTION -> deliveryOnly.contains(merchantId)
                    ? OrderLineRejection.collectionNotOffered(listing.getId(), listing.getTitle(),
                            line.quantity(), unitPriceCents, line.variantId(), variantLabel,
                            merchantId)
                    : null;
        };
    }

    /**
     * Each on-sale seller's share, in basket order: the method they were priced
     * with, their fee, whether they collect, and the cell's methods that could
     * reach the buyer - COLLECTION when they collect, DELIVERY when every one
     * of their on-sale lines is delivered to {@code townCode} (or, with no town
     * known, delivered somewhere). Computed from the very rows the lines were
     * priced from, so a method listed here is one that prices without a method
     * issue for this seller.
     */
    private Map<UUID, SellerPricing> sellerPricing(LoadedBasket basket, DeliveryPlan plan,
                                                          Map<UUID, Map<String, Long>> coverage,
                                                          Set<UUID> deliveryOnly, String townCode,
                                                          Map<UUID, Long> feeByMerchant) {
        Map<UUID, Boolean> delivers = new HashMap<>();
        for (BasketLine line : basket.requested()) {
            Listing listing = basket.listings().get(line.listingId());
            if (!onSale(listing)) {
                continue;
            }
            Map<String, Long> served = coverage.getOrDefault(listing.getId(), Map.of());
            boolean covered = townCode == null ? !served.isEmpty() : served.containsKey(townCode);
            delivers.merge(listing.getMerchantId(), covered, Boolean::logicalAnd);
        }
        Map<UUID, SellerPricing> out = new LinkedHashMap<>();
        for (UUID merchantId : basket.sellers()) {
            DeliveryMethod method = plan.methodFor(merchantId);
            boolean collects = !deliveryOnly.contains(merchantId);
            boolean deliversHere = delivers.getOrDefault(merchantId, false);
            List<DeliveryMethod> available = plan.offered().stream()
                    .filter(m -> m == DeliveryMethod.COLLECTION ? collects : deliversHere)
                    .toList();
            out.put(merchantId, new SellerPricing(merchantId, method,
                    method == DeliveryMethod.DELIVERY ? feeByMerchant.get(merchantId) : null,
                    collects, available));
        }
        return Collections.unmodifiableMap(out);
    }

    /** The on-sale lines' listings, once each, in basket order. */
    private List<UUID> onSaleListingIds(LoadedBasket basket) {
        return basket.requested().stream()
                .map(line -> basket.listings().get(line.listingId()))
                .filter(this::onSale)
                .map(Listing::getId)
                .distinct()
                .toList();
    }

    private Map<UUID, Map<String, Long>> coverageOf(List<UUID> listingIds) {
        Map<UUID, Map<String, Long>> byListing = new HashMap<>();
        for (ListingDeliveryTown row : deliveryTownRepository.findByListingIdIn(listingIds)) {
            byListing.computeIfAbsent(row.getListingId(), id -> new HashMap<>())
                    .put(row.getTownCode(), row.getFeeCents());
        }
        return byListing;
    }

    /** The ONE definition of "on sale": exists, ACTIVE, and in this cell's
     *  currency. Anything else is {@code LISTING_UNAVAILABLE} and names no
     *  seller. */
    private boolean onSale(Listing listing) {
        return listing != null
                && listing.getStatus() == ListingStatus.ACTIVE
                && currency.equals(listing.getCurrency());
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
        if (!onSale(listing)) {
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
