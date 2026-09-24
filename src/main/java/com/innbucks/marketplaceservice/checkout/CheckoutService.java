package com.innbucks.marketplaceservice.checkout;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.cart.CartService;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.checkout.dto.CheckoutOptionsResponse;
import com.innbucks.marketplaceservice.checkout.dto.CheckoutQuoteRequest;
import com.innbucks.marketplaceservice.checkout.dto.CheckoutQuoteResponse;
import com.innbucks.marketplaceservice.checkout.dto.PaymentInstruction;
import com.innbucks.marketplaceservice.checkout.dto.PaymentOption;
import com.innbucks.marketplaceservice.checkout.dto.PricedLineResponse;
import com.innbucks.marketplaceservice.delivery.DeliveryAddress;
import com.innbucks.marketplaceservice.delivery.DeliveryAddressService;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.delivery.dto.AddressResponse;
import com.innbucks.marketplaceservice.pickup.CollectionPoint;
import com.innbucks.marketplaceservice.pickup.CollectionPointResolver;
import com.innbucks.marketplaceservice.pickup.CollectionPointViews;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointChoice;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointResponse;
import com.innbucks.marketplaceservice.pickup.dto.SellerCollectionPoint;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Checkout: turning a basket into a priced, addressed, payable thing.
 *
 * <p>Everything here is SHARED with order creation rather than parallel to it —
 * where the lines come from, which delivery method applies, what it costs,
 * which address it goes to, and how it can be paid for are all resolved by the
 * methods below, which the order flow calls too. A quote that said $49.98 and
 * an order that then charged $51.98 would be the single worst bug this surface
 * could have, and two copies of this logic is how that bug gets written.
 */
@Service
public class CheckoutService {

    /**
     * The payments service's addressing for a marketplace order. Named here
     * rather than in the app because it is OUR order ref that must be sent, and
     * the value the payments service matches on — see {@link PaymentInstruction}
     * for why the app should not be carrying this knowledge itself.
     */
    static final String PAYMENTS_ENDPOINT = "POST /payments";
    static final String PAYMENTS_ORDER_TYPE = "MARKETPLACE";

    private final CheckoutProperties properties;
    private final CheckoutPricer pricer;
    private final BasketViewAssembler basketViews;
    private final CartService cartService;
    private final DeliveryAddressService addressService;
    private final CollectionPointResolver collectionPoints;
    private final CollectionPointViews collectionPointViews;
    private final String currency;

    public CheckoutService(CheckoutProperties properties,
                           CheckoutPricer pricer,
                           BasketViewAssembler basketViews,
                           CartService cartService,
                           DeliveryAddressService addressService,
                           CollectionPointResolver collectionPoints,
                           CollectionPointViews collectionPointViews,
                           @Value("${innbucks.currency}") String currency) {
        this.properties = properties;
        this.pricer = pricer;
        this.basketViews = basketViews;
        this.cartService = cartService;
        this.addressService = addressService;
        this.collectionPoints = collectionPoints;
        this.collectionPointViews = collectionPointViews;
        this.currency = currency;
    }

    // ------------------------------------------------------------------
    // Quote — the checkout screen, priced and totalled, reserving nothing
    // ------------------------------------------------------------------

    /**
     * Prices a basket and totals it. Reserves NOTHING: quoting is free and
     * repeatable, and the order is the commitment.
     *
     * <p>Before this existed the only way to discover an item was out of stock
     * was to CREATE an order, which reserves stock as a side effect — so "let
     * me just see the total" cost a merchant a hold on their inventory, and a
     * shopper comparing delivery against collection placed two orders to do it.
     */
    @Transactional(readOnly = true)
    public CheckoutQuoteResponse quote(AuthenticatedUser buyer, CheckoutQuoteRequest request) {
        List<BasketLine> basket = resolveBasket(buyer, request.sourcedFromCart(),
                request.items() == null ? List.of()
                        : request.items().stream()
                                .map(item -> new BasketLine(item.listingId(), item.quantity(),
                                        item.variantId()))
                                .toList());
        DeliveryMethod method = resolveMethod(request.deliveryMethod());
        // Resolved even when the basket turns out unbuyable: a shopper fixing a
        // sold-out line must not ALSO lose the address they just picked.
        DeliveryAddress address = resolveAddress(buyer, method, request.deliveryAddressId());

        PricedBasket priced = pricer.price(basket, method,
                address == null ? null : address.getTownCode());
        long deliveryFee = priced.deliveryFeeCents();
        long total = Math.addExact(priced.subtotalCents(), deliveryFee);
        List<PricedLineResponse> lines = basketViews.toLines(priced, Map.of());

        return new CheckoutQuoteResponse(
                lines,
                lines.size(),
                BasketViewAssembler.totalQuantity(priced),
                priced.subtotalCents(),
                deliveryFee,
                total,
                currency,
                method,
                List.copyOf(offeredMethods()),
                address == null ? null : AddressResponse.from(address),
                priced.issues().isEmpty() ? null : priced.issues(),
                priced.checkoutReady(),
                paymentOptions(),
                priced.deliveryFeesByMerchant().entrySet().stream()
                        .map(e -> new CheckoutQuoteResponse.SellerDeliveryFee(e.getKey(), e.getValue()))
                        .toList(),
                method == DeliveryMethod.COLLECTION
                        ? collectionView(priced, resolveCollectionPoints(method, priced,
                                request.collectionPoints()))
                        : null);
    }

    /**
     * Where each seller's goods are collected, for a COLLECTION basket — the
     * ONE definition the quote and the order share, so the point a buyer was
     * quoted is the point their order records. Empty for DELIVERY. Resolved
     * BEFORE any stock is touched: a bad choice refuses the order cleanly.
     *
     * @throws ApiException 400 {@code unknown_collection_point} /
     *         {@code duplicate_collection_point_choice}
     */
    public Map<UUID, CollectionPoint> resolveCollectionPoints(DeliveryMethod method,
                                                              PricedBasket priced,
                                                              List<CollectionPointChoice> choices) {
        if (method != DeliveryMethod.COLLECTION) {
            return Map.of();
        }
        return collectionPoints.resolve(sellersOf(priced), choices);
    }

    /** Copies each resolved point onto the order (V18 snapshot). No-op for none. */
    public void recordCollectionPoints(UUID orderId, Map<UUID, CollectionPoint> resolved) {
        collectionPoints.record(orderId, resolved);
    }

    /** Every seller in the basket, in basket order, with where they are collected. */
    private List<SellerCollectionPoint> collectionView(PricedBasket priced,
                                                       Map<UUID, CollectionPoint> resolved) {
        List<CollectionPoint> chosen = List.copyOf(resolved.values());
        Map<UUID, CollectionPointResponse> byId = new LinkedHashMap<>();
        List<CollectionPointResponse> rendered = collectionPointViews.render(chosen);
        for (int i = 0; i < chosen.size(); i++) {
            byId.put(chosen.get(i).getMerchantId(), rendered.get(i));
        }
        return CollectionPointViews.perSeller(sellersOf(priced), byId);
    }

    private static List<UUID> sellersOf(PricedBasket priced) {
        return priced.lines().stream()
                .map(PricedLine::listing)
                .filter(Objects::nonNull)
                .map(Listing::getMerchantId)
                .distinct()
                .toList();
    }

    // ------------------------------------------------------------------
    // Shared with order creation — one definition per question
    // ------------------------------------------------------------------

    /**
     * Where the lines come from.
     *
     * <p>Exactly one source: sending both the cart flag and explicit items is
     * REFUSED rather than silently resolved in favour of one, because a client
     * that believes it sent a Buy Now and got the whole cart has bought things
     * the shopper never confirmed.
     */
    public List<BasketLine> resolveBasket(AuthenticatedUser buyer, boolean fromCart,
                                          List<BasketLine> explicit) {
        boolean hasExplicit = explicit != null && !explicit.isEmpty();
        if (fromCart && hasExplicit) {
            throw ApiException.badRequest("ambiguous_basket",
                    "Send either fromCart or items, not both");
        }
        if (fromCart) {
            List<BasketLine> cart = cartService.basketOf(buyer);
            if (cart.isEmpty()) {
                throw ApiException.badRequest("cart_empty", "Your cart is empty");
            }
            return cart;
        }
        if (!hasExplicit) {
            throw ApiException.badRequest("invalid_items",
                    "Send either fromCart or at least one item");
        }
        return explicit;
    }

    /**
     * The delivery method to use, defaulted and validated against what the cell
     * offers. A cell with no courier arrangement drops DELIVERY from its
     * configuration and checkout stops offering it — rather than accepting the
     * choice and then having nobody to ship with.
     *
     * <p><b>An unstated method is COLLECTION, deliberately.</b> It is exactly
     * what an order carried before delivery existed here — no destination was
     * ever captured, so nothing was ever going to be shipped — which is why V9
     * backfills every pre-existing order the same way. A client that has not
     * been updated therefore keeps behaving identically, where a DELIVERY
     * default would 400 it for an address it does not know to send. Wanting
     * something delivered is an explicit choice; it has to be, because it costs
     * money and needs somewhere to go.
     */
    public DeliveryMethod resolveMethod(DeliveryMethod requested) {
        Set<DeliveryMethod> offered = offeredMethods();
        if (offered.isEmpty()) {
            // Misconfiguration, not a buyer error: a cell that offers no way to
            // receive goods cannot sell any. Refused with a message that sends
            // nobody hunting through their own cart for the cause.
            throw ApiException.unprocessable("delivery_unavailable",
                    "Checkout is not available right now. Please try again later.");
        }
        if (requested == null) {
            return offered.contains(DeliveryMethod.COLLECTION)
                    ? DeliveryMethod.COLLECTION
                    : offered.iterator().next();
        }
        if (!offered.contains(requested)) {
            throw ApiException.unprocessable("delivery_method_unavailable",
                    requested + " is not available in this market");
        }
        return requested;
    }

    /**
     * The address a DELIVERY order goes to, or null for COLLECTION. Resolved
     * identically for the quote and the order, so an order can never land
     * somewhere the quote did not show.
     */
    @Transactional(readOnly = true)
    public DeliveryAddress resolveAddress(AuthenticatedUser buyer, DeliveryMethod method,
                                          UUID addressId) {
        if (method != DeliveryMethod.DELIVERY) {
            return null;
        }
        DeliveryAddress address = addressService.requireForCheckout(buyer, addressId);
        if (address.getTownCode() == null) {
            // A pre-V14 address whose free-text city matched no town: there is
            // no way to say who delivers there, so it is refused, not guessed.
            throw ApiException.unprocessable("address_town_required",
                    "Choose the town for this address before using it for delivery");
        }
        return address;
    }

    /** The rails this cell is provisioned for, in the order to offer them. */
    public List<PaymentOption> paymentOptions() {
        return properties.getCheckout().getPaymentMethods().stream()
                .distinct()
                .map(PaymentOption::of)
                .toList();
    }

    /** How the goods may be received on this cell. */
    public List<DeliveryMethod> deliveryMethods() {
        return List.copyOf(offeredMethods());
    }

    /**
     * The per-cell checkout picture, fetched once and cached by the app. Reads
     * the SAME configuration the quote and the order read, so what the app
     * offers and what the order accepts cannot drift.
     */
    public CheckoutOptionsResponse options() {
        return new CheckoutOptionsResponse(
                deliveryMethods(),
                // Deprecated field: delivery is priced per seller per town now.
                0L,
                currency,
                paymentOptions(),
                PAYMENTS_ENDPOINT,
                PAYMENTS_ORDER_TYPE);
    }

    /**
     * The "how do I pay for this" block carried on an order that is awaiting
     * payment. Marketplace-service collects nothing — this only names the call
     * the app must make to the payments service and the rails it may pick.
     */
    public PaymentInstruction paymentInstruction(String orderRef, long totalCents,
                                                 String currency, Instant payBefore) {
        return new PaymentInstruction(PAYMENTS_ENDPOINT, PAYMENTS_ORDER_TYPE, orderRef,
                totalCents, currency, payBefore, paymentOptions());
    }

    private Set<DeliveryMethod> offeredMethods() {
        return properties.getDelivery().getMethods();
    }
}
