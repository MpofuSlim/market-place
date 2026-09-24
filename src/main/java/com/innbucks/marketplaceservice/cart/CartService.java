package com.innbucks.marketplaceservice.cart;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.cart.dto.CartResponse;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository;
import com.innbucks.marketplaceservice.checkout.BasketLine;
import com.innbucks.marketplaceservice.checkout.BasketViewAssembler;
import com.innbucks.marketplaceservice.checkout.CheckoutPricer;
import com.innbucks.marketplaceservice.checkout.LineKey;
import com.innbucks.marketplaceservice.checkout.PricedBasket;
import com.innbucks.marketplaceservice.checkout.dto.PricedLineResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The buyer's cart.
 *
 * <p><b>The cart stores quantities and nothing else.</b> Price, title, stock
 * and status are resolved live on every read through {@link CheckoutPricer} —
 * the same resolver the checkout quote and order creation use, so the three
 * screens a shopper sees in a row can never disagree about whether something
 * is available. A price copied into the cart would quietly become a promise
 * the catalogue no longer makes; the snapshot belongs on the ORDER, where the
 * buyer has agreed to it.
 *
 * <p><b>The cart holds no stock.</b> Reservation happens once, atomically, at
 * order creation. A cart that reserved would let anyone freeze a merchant's
 * inventory for free, and a shopper who leaves a tab open would take goods off
 * sale for everyone else.
 *
 * <p>Scoped to the caller by shape: the buyer uuid comes from the JWT and no
 * path or query parameter names a user, so there is nothing to point at
 * someone else's cart.
 */
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartItemRepository cartRepository;
    private final ListingRepository listingRepository;
    private final CheckoutPricer pricer;
    private final BasketViewAssembler basketViews;
    /** V19: lines that name an option live in their own table — see
     *  {@link CartVariantItem} for why it is a sibling, not a change. */
    private final CartVariantItemRepository variantCartRepository;
    private final ListingVariantRepository variantRepository;

    /** Same caps the order flow enforces, read from the SAME properties — a
     *  cart a shopper cannot check out with is worse than a refused add. */
    @Value("${marketplace.order.max-items}")
    private int maxItems;

    @Value("${marketplace.order.max-quantity-per-item}")
    private int maxQuantityPerItem;

    @Value("${innbucks.currency}")
    private String currency;

    @Transactional(readOnly = true)
    public CartResponse getCart(AuthenticatedUser buyer) {
        return render(linesOf(buyerId(buyer)));
    }

    /** {@link #add(AuthenticatedUser, UUID, UUID, int)} on a listing without
     *  options — the pre-V19 call. */
    @Transactional
    public CartResponse add(AuthenticatedUser buyer, UUID listingId, int quantity) {
        return add(buyer, listingId, null, quantity);
    }

    /**
     * Adds units to the cart. The listing must EXIST; it need not be ACTIVE or
     * in stock — the same stance favorites take. An item that goes out of stock
     * while it sits in the cart must stay visible with a "sold out" badge,
     * because silently dropping it is how a shopper arrives at checkout with a
     * total they do not recognise. The cart read carries the issue; checkout is
     * where it is finally refused.
     *
     * <p>V19: a listing that sells options needs one named ({@code variantId},
     * 400 {@code variant_required} otherwise) and it must be one of THAT
     * listing's (404 {@code variant_not_found}). Two options of one listing are
     * two lines, each with its own cap.
     */
    @Transactional
    public CartResponse add(AuthenticatedUser buyer, UUID listingId, UUID variantId, int quantity) {
        UUID buyerUuid = buyerId(buyer);
        requireLine(listingId, variantId);
        requireQuantity(quantity);
        Instant now = Instant.now();
        if (variantId == null) {
            if (!cartRepository.existsById(new CartItemId(buyerUuid, listingId))) {
                requireRoom(buyerUuid);
            }
            cartRepository.addQuantity(buyerUuid, listingId, quantity, maxQuantityPerItem, now);
        } else {
            if (!variantCartRepository.existsById(new CartVariantItemId(buyerUuid, variantId))) {
                requireRoom(buyerUuid);
            }
            variantCartRepository.addQuantity(buyerUuid, variantId, listingId, quantity,
                    maxQuantityPerItem, now);
        }
        return getCartAfterWrite(buyerUuid);
    }

    /** Sets a line without an option to an exact quantity — the pre-V19 call. */
    @Transactional
    public CartResponse setQuantity(AuthenticatedUser buyer, UUID listingId, int quantity) {
        return setQuantity(buyer, listingId, null, quantity);
    }

    /** Sets a line to an exact quantity, creating it if absent. */
    @Transactional
    public CartResponse setQuantity(AuthenticatedUser buyer, UUID listingId, UUID variantId,
                                    int quantity) {
        UUID buyerUuid = buyerId(buyer);
        requireLine(listingId, variantId);
        requireQuantity(quantity);
        if (quantity > maxQuantityPerItem) {
            // Refused rather than clamped, unlike add: the shopper named this
            // exact number, so silently storing a different one would make the
            // stepper disagree with what it shows.
            throw ApiException.badRequest("invalid_quantity",
                    "You can buy up to " + maxQuantityPerItem + " of one item per order");
        }
        Instant now = Instant.now();
        if (variantId == null) {
            if (!cartRepository.existsById(new CartItemId(buyerUuid, listingId))) {
                requireRoom(buyerUuid);
            }
            cartRepository.setQuantity(buyerUuid, listingId, quantity, now);
        } else {
            if (!variantCartRepository.existsById(new CartVariantItemId(buyerUuid, variantId))) {
                requireRoom(buyerUuid);
            }
            variantCartRepository.setQuantity(buyerUuid, variantId, listingId, quantity, now);
        }
        return getCartAfterWrite(buyerUuid);
    }

    /** Removes every line of a listing — the pre-V19 call. */
    @Transactional
    public CartResponse remove(AuthenticatedUser buyer, UUID listingId) {
        return remove(buyer, listingId, null);
    }

    /**
     * Idempotent: removing a line that is not there is a 200 no-op, so the
     * app's remove button can retry blindly. Without {@code variantId} this
     * keeps its pre-V19 meaning, "remove this item": the plain line AND every
     * option line of the listing, so an app that never learned about options
     * can still clear a line a newer one added. With it, exactly that line.
     */
    @Transactional
    public CartResponse remove(AuthenticatedUser buyer, UUID listingId, UUID variantId) {
        UUID buyerUuid = buyerId(buyer);
        if (variantId == null) {
            cartRepository.remove(buyerUuid, listingId);
            variantCartRepository.removeAllOfListing(buyerUuid, listingId);
        } else {
            variantCartRepository.remove(buyerUuid, variantId);
        }
        return getCartAfterWrite(buyerUuid);
    }

    /** Idempotent: clearing an empty cart is a 200 no-op. */
    @Transactional
    public CartResponse clear(AuthenticatedUser buyer) {
        UUID buyerUuid = buyerId(buyer);
        cartRepository.clear(buyerUuid);
        variantCartRepository.clear(buyerUuid);
        return render(List.of());
    }

    /**
     * The cart as basket lines, for the checkout quote and for
     * {@code fromCart} order creation. Most-recently-added first, the same
     * order the shopper sees — so the lines of a refusal line up with the
     * screen they are reading.
     */
    @Transactional(readOnly = true)
    public List<BasketLine> basketOf(AuthenticatedUser buyer) {
        return linesOf(buyerId(buyer)).stream().map(CartLine::basketLine).toList();
    }

    /**
     * Removes exactly the lines an order took, leaving anything the shopper did
     * not check out.
     *
     * <p>Called AFTER the order commits, deliberately: a cart cleared inside
     * the order transaction and then rolled back would look to the shopper like
     * their cart vanished and their order failed. Losing this call entirely
     * (a crash between commit and here) leaves stale lines the shopper can
     * remove themselves — strictly the better failure.
     */
    @Transactional
    public void removeOrdered(UUID buyerUuid, Collection<UUID> listingIds) {
        if (listingIds.isEmpty()) {
            return;
        }
        cartRepository.removeAll(buyerUuid, listingIds);
    }

    /** {@link #removeOrdered} for the option lines an order took (V19). */
    @Transactional
    public void removeOrderedVariants(UUID buyerUuid, Collection<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return;
        }
        variantCartRepository.removeAll(buyerUuid, variantIds);
    }

    /** Re-reads and renders after a mutation, so a write and the cart it
     *  produces are one round trip. The repository's mutations clear the
     *  persistence context, so this read sees the row the database holds. */
    private CartResponse getCartAfterWrite(UUID buyerUuid) {
        return render(linesOf(buyerUuid));
    }

    /** One cart line from either table. */
    private record CartLine(UUID listingId, UUID variantId, int quantity, Instant addedAt) {

        BasketLine basketLine() {
            return new BasketLine(listingId, quantity, variantId);
        }
    }

    /**
     * Both tables' lines, most-recently-added first. When only one table has
     * rows its repository order is kept verbatim (so a cart without options
     * reads exactly as before V19); otherwise the two are merged by
     * {@code addedAt}, stably.
     */
    private List<CartLine> linesOf(UUID buyerUuid) {
        List<CartLine> plain = cartRepository.findByBuyerUuidOrderByAddedAtDesc(buyerUuid).stream()
                .map(item -> new CartLine(item.getListingId(), null, item.getQuantity(),
                        item.getAddedAt()))
                .toList();
        List<CartLine> options = variantCartRepository.findByBuyerUuidOrderByAddedAtDesc(buyerUuid)
                .stream()
                .map(item -> new CartLine(item.getListingId(), item.getVariantId(),
                        item.getQuantity(), item.getAddedAt()))
                .toList();
        if (options.isEmpty()) {
            return plain;
        }
        if (plain.isEmpty()) {
            return options;
        }
        List<CartLine> merged = new ArrayList<>(plain.size() + options.size());
        merged.addAll(plain);
        merged.addAll(options);
        merged.sort(Comparator.comparing(CartLine::addedAt,
                Comparator.nullsLast(Comparator.<Instant>reverseOrder())));
        return merged;
    }

    private CartResponse render(List<CartLine> items) {
        PricedBasket priced = pricer.price(items.stream().map(CartLine::basketLine).toList());
        Map<LineKey, Instant> addedAt = new LinkedHashMap<>();
        items.forEach(item -> addedAt.put(new LineKey(item.listingId(), item.variantId()),
                item.addedAt()));
        List<PricedLineResponse> lines = basketViews.toLines(priced, addedAt);
        return new CartResponse(lines, lines.size(), BasketViewAssembler.totalQuantity(priced),
                priced.subtotalCents(), currency, priced.checkoutReady());
    }

    /**
     * The listing must exist (404 {@code listing_not_found}). V19: a listing
     * that sells options needs one named (400 {@code variant_required}); a
     * named option must be one of that listing's, and a listing without
     * options has none (404 {@code variant_not_found} — the same answer for
     * "missing" and "not this listing's", so nothing is disclosed about other
     * listings' option ids).
     */
    private void requireLine(UUID listingId, UUID variantId) {
        if (!listingRepository.existsById(listingId)) {
            throw ApiException.notFound("listing_not_found", "Listing not found");
        }
        boolean sellsOptions = listingRepository.existsByIdAndHasVariantsTrue(listingId);
        if (sellsOptions && variantId == null) {
            throw ApiException.badRequest("variant_required",
                    "Choose an option before adding this item to your cart");
        }
        if (variantId != null
                && (!sellsOptions || !variantRepository.existsByIdAndListingId(variantId, listingId))) {
            throw ApiException.notFound("variant_not_found", "Variant not found");
        }
    }

    private void requireQuantity(int quantity) {
        if (quantity < 1) {
            throw ApiException.badRequest("invalid_quantity", "Quantity must be at least 1");
        }
    }

    /** The cart cannot exceed what an order may carry, or the shopper fills it
     *  and then discovers at checkout that it can never be bought. Counts LINES
     *  across both tables: two sizes of one listing are two of the cap. */
    private void requireRoom(UUID buyerUuid) {
        if (cartRepository.countByBuyerUuid(buyerUuid)
                + variantCartRepository.countByBuyerUuid(buyerUuid) >= maxItems) {
            throw ApiException.conflict("cart_full",
                    "Your cart holds the maximum of " + maxItems
                            + " different items. Remove one, or check out what you have.");
        }
    }

    private static UUID buyerId(AuthenticatedUser buyer) {
        return UUID.fromString(buyer.uuid());
    }
}
