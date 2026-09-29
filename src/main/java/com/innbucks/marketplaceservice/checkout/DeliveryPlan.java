package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How each seller's share of a basket reaches the buyer, decided BEFORE the
 * basket is priced (V20: load, then plan, then price).
 *
 * <p>A request with no per-seller choices is {@link #uniform}: the basket's one
 * method applies to every seller, which is exactly what an order meant before
 * V20. A request that names a method per seller
 * ({@code sellerDeliveryMethods}) is {@link #forSellers}: {@link #methodFor} is
 * the only way the pricer asks, and {@link #needsDestination} /
 * {@link #summary} / {@link #someoneCollects} are the only way the quote and
 * order creation ask. When {@code bySeller} is non-empty it names
 * EVERY seller in the basket; when it is empty, {@code basketDefault} applies
 * to all of them. Build a per-seller plan only through {@link #forSellers},
 * which makes that true by construction: with a PARTIAL map, a seller left to
 * the default could deliver while {@link #needsDestination} (which reads the
 * map) said nobody does, and the order would be priced for a delivery with no
 * address to send it to.
 *
 * <p>{@link #NONE} is the cart's plan: no method chosen at all. The pricer then
 * reads neither delivery coverage nor seller settings, so the cart's
 * {@code checkoutReady} can never flip on a delivery question the shopper has
 * not been asked yet.
 *
 * @param basketDefault the method for every seller not named in {@code bySeller};
 *                      null only for {@link #NONE}
 * @param bySeller      per-seller methods (empty for a uniform plan)
 * @param offered       the methods this cell offers - what a seller's
 *                      {@code availableMethods} is drawn from
 */
public record DeliveryPlan(DeliveryMethod basketDefault,
                           Map<UUID, DeliveryMethod> bySeller,
                           Set<DeliveryMethod> offered) {

    /** The cart: method-agnostic, prices goods only, asks nothing about delivery. */
    public static final DeliveryPlan NONE = new DeliveryPlan(null, Map.of(), Set.of());

    public DeliveryPlan {
        bySeller = bySeller == null ? Map.of() : Map.copyOf(bySeller);
        // Order kept: availableMethods lists methods in the cell's own order,
        // the same order the quote's deliveryMethods uses.
        offered = offered == null ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(offered));
    }

    /** One method for the whole basket - every plan before per-seller choices. */
    public static DeliveryPlan uniform(DeliveryMethod method, Set<DeliveryMethod> offered) {
        if (method == null) {
            throw new IllegalArgumentException("A checkout plan needs a delivery method");
        }
        return new DeliveryPlan(method, Map.of(), offered);
    }

    /**
     * A per-seller plan naming EVERY seller in {@code sellers}: each takes its
     * entry in {@code choices}, or {@code basketDefault} when it has none. A
     * choice for a seller not in {@code sellers} (no longer in the basket) is
     * dropped. With no choices this is exactly {@link #uniform}.
     */
    public static DeliveryPlan forSellers(DeliveryMethod basketDefault,
                                          Map<UUID, DeliveryMethod> choices,
                                          Collection<UUID> sellers,
                                          Set<DeliveryMethod> offered) {
        if (choices == null || choices.isEmpty()) {
            return uniform(basketDefault, offered);
        }
        if (basketDefault == null) {
            throw new IllegalArgumentException("A checkout plan needs a delivery method");
        }
        Map<UUID, DeliveryMethod> every = new LinkedHashMap<>();
        for (UUID seller : sellers) {
            every.put(seller, choices.getOrDefault(seller, basketDefault));
        }
        return every.isEmpty() ? uniform(basketDefault, offered)
                : new DeliveryPlan(basketDefault, every, offered);
    }

    /** True for the cart's plan, which prices goods and nothing else. */
    public boolean isNone() {
        return basketDefault == null;
    }

    /** How {@code merchantId}'s goods reach the buyer; null only on {@link #NONE}. */
    public DeliveryMethod methodFor(UUID merchantId) {
        return bySeller.getOrDefault(merchantId, basketDefault);
    }

    /** Whether some seller delivers, so the order needs somewhere to send it. */
    public boolean needsDestination() {
        return bySeller.isEmpty()
                ? basketDefault == DeliveryMethod.DELIVERY
                : bySeller.containsValue(DeliveryMethod.DELIVERY);
    }

    /**
     * Whether some seller collects, so the checkout says where. On a uniform
     * plan this is exactly {@code summary() == COLLECTION}, which is what the
     * quote and the order have always keyed {@code collectionPoints} on.
     */
    public boolean someoneCollects() {
        return bySeller.isEmpty()
                ? basketDefault == DeliveryMethod.COLLECTION
                : bySeller.containsValue(DeliveryMethod.COLLECTION);
    }

    /**
     * True when the plan's sellers do NOT all share one method - one seller
     * delivers and another collects. A per-seller plan whose choices all agree
     * is uniform in effect, and reads false.
     */
    public boolean isMixed() {
        return bySeller.containsValue(DeliveryMethod.DELIVERY)
                && bySeller.containsValue(DeliveryMethod.COLLECTION);
    }

    /**
     * The order-level method: DELIVERY when any seller delivers, else
     * COLLECTION. There is no "mixed" value - DELIVERY keeps the order's
     * destination CHECK guarding every order that ships a parcel. Equal to the
     * requested method on every uniform plan. Null only on {@link #NONE}.
     */
    public DeliveryMethod summary() {
        if (isNone()) {
            return null;
        }
        return needsDestination() ? DeliveryMethod.DELIVERY : DeliveryMethod.COLLECTION;
    }
}
