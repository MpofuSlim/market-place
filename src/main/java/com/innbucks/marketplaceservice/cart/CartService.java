package com.innbucks.marketplaceservice.cart;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.cart.dto.CartResponse;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.checkout.BasketLine;
import com.innbucks.marketplaceservice.checkout.BasketViewAssembler;
import com.innbucks.marketplaceservice.checkout.CheckoutPricer;
import com.innbucks.marketplaceservice.checkout.PricedBasket;
import com.innbucks.marketplaceservice.checkout.dto.PricedLineResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
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
        return render(cartRepository.findByBuyerUuidOrderByAddedAtDesc(buyerId(buyer)));
    }

    /**
     * Adds units to the cart. The listing must EXIST; it need not be ACTIVE or
     * in stock — the same stance favorites take. An item that goes out of stock
     * while it sits in the cart must stay visible with a "sold out" badge,
     * because silently dropping it is how a shopper arrives at checkout with a
     * total they do not recognise. The cart read carries the issue; checkout is
     * where it is finally refused.
     */
    @Transactional
    public CartResponse add(AuthenticatedUser buyer, UUID listingId, int quantity) {
        UUID buyerUuid = buyerId(buyer);
        requireListing(listingId);
        requireQuantity(quantity);
        if (!cartRepository.existsById(new CartItemId(buyerUuid, listingId))) {
            requireRoom(buyerUuid);
        }
        cartRepository.addQuantity(buyerUuid, listingId, quantity, maxQuantityPerItem, Instant.now());
        return getCartAfterWrite(buyerUuid);
    }

    /** Sets a line to an exact quantity, creating it if absent. */
    @Transactional
    public CartResponse setQuantity(AuthenticatedUser buyer, UUID listingId, int quantity) {
        UUID buyerUuid = buyerId(buyer);
        requireListing(listingId);
        requireQuantity(quantity);
        if (quantity > maxQuantityPerItem) {
            // Refused rather than clamped, unlike add: the shopper named this
            // exact number, so silently storing a different one would make the
            // stepper disagree with what it shows.
            throw ApiException.badRequest("invalid_quantity",
                    "You can buy up to " + maxQuantityPerItem + " of one item per order");
        }
        if (!cartRepository.existsById(new CartItemId(buyerUuid, listingId))) {
            requireRoom(buyerUuid);
        }
        cartRepository.setQuantity(buyerUuid, listingId, quantity, Instant.now());
        return getCartAfterWrite(buyerUuid);
    }

    /** Idempotent: removing a line that is not there is a 200 no-op, so the
     *  app's remove button can retry blindly. */
    @Transactional
    public CartResponse remove(AuthenticatedUser buyer, UUID listingId) {
        UUID buyerUuid = buyerId(buyer);
        cartRepository.remove(buyerUuid, listingId);
        return getCartAfterWrite(buyerUuid);
    }

    /** Idempotent: clearing an empty cart is a 200 no-op. */
    @Transactional
    public CartResponse clear(AuthenticatedUser buyer) {
        UUID buyerUuid = buyerId(buyer);
        cartRepository.clear(buyerUuid);
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
        return cartRepository.findByBuyerUuidOrderByAddedAtDesc(buyerId(buyer)).stream()
                .map(item -> new BasketLine(item.getListingId(), item.getQuantity()))
                .toList();
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

    /** Re-reads and renders after a mutation, so a write and the cart it
     *  produces are one round trip. The repository's mutations clear the
     *  persistence context, so this read sees the row the database holds. */
    private CartResponse getCartAfterWrite(UUID buyerUuid) {
        return render(cartRepository.findByBuyerUuidOrderByAddedAtDesc(buyerUuid));
    }

    private CartResponse render(List<CartItem> items) {
        PricedBasket priced = pricer.price(items.stream()
                .map(item -> new BasketLine(item.getListingId(), item.getQuantity()))
                .toList());
        Map<UUID, Instant> addedAt = new LinkedHashMap<>();
        items.forEach(item -> addedAt.put(item.getListingId(), item.getAddedAt()));
        List<PricedLineResponse> lines = basketViews.toLines(priced, addedAt);
        return new CartResponse(lines, lines.size(), BasketViewAssembler.totalQuantity(priced),
                priced.subtotalCents(), currency, priced.checkoutReady());
    }

    private void requireListing(UUID listingId) {
        if (!listingRepository.existsById(listingId)) {
            throw ApiException.notFound("listing_not_found", "Listing not found");
        }
    }

    private void requireQuantity(int quantity) {
        if (quantity < 1) {
            throw ApiException.badRequest("invalid_quantity", "Quantity must be at least 1");
        }
    }

    /** The cart cannot exceed what an order may carry, or the shopper fills it
     *  and then discovers at checkout that it can never be bought. */
    private void requireRoom(UUID buyerUuid) {
        if (cartRepository.countByBuyerUuid(buyerUuid) >= maxItems) {
            throw ApiException.conflict("cart_full",
                    "Your cart holds the maximum of " + maxItems
                            + " different items. Remove one, or check out what you have.");
        }
    }

    private static UUID buyerId(AuthenticatedUser buyer) {
        return UUID.fromString(buyer.uuid());
    }
}
