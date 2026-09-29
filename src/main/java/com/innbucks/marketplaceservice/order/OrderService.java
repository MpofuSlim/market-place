package com.innbucks.marketplaceservice.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.cart.CartService;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingStock;
import com.innbucks.marketplaceservice.catalog.StockLine;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.checkout.BasketLine;
import com.innbucks.marketplaceservice.checkout.CheckoutPricer;
import com.innbucks.marketplaceservice.checkout.CheckoutService;
import com.innbucks.marketplaceservice.checkout.DeliveryPlan;
import com.innbucks.marketplaceservice.checkout.LineKey;
import com.innbucks.marketplaceservice.checkout.LoadedBasket;
import com.innbucks.marketplaceservice.checkout.PricedBasket;
import com.innbucks.marketplaceservice.checkout.SellerPricing;
import com.innbucks.marketplaceservice.delivery.DeliveryAddress;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentService;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.idempotency.ClaimResult;
import com.innbucks.marketplaceservice.idempotency.IdempotencyService;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.dto.ConfirmPaymentRequest;
import com.innbucks.marketplaceservice.order.dto.CreateOrderRequest;
import com.innbucks.marketplaceservice.order.dto.InternalOrderView;
import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;
import com.innbucks.marketplaceservice.order.dto.OrderRejectionDetails;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import com.innbucks.marketplaceservice.pickup.CollectionPoint;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.seller.NameResolvingRead;
import com.innbucks.marketplaceservice.settlement.SettlementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Order domain operations: the buyer surface (create / cancel / read own) and
 * the S2S surface the platform payments service drives by {@code orderRef}
 * (read / extend-expiry / confirm-payment).
 *
 * <h2>Creation shape</h2>
 * {@code createOrder} is deliberately NOT {@code @Transactional}: the
 * idempotency claim must commit OUTSIDE the business transaction (autocommit)
 * so a concurrent same-key request sees the live claim immediately (409)
 * instead of blocking on our row insert for the whole request. The business
 * work (validate → reserve stock → persist order+items+journal) runs inside
 * a {@link TransactionTemplate}; the claim is completed with the serialized
 * response FIRST thing after that transaction commits, and released if it
 * throws. Everything after the commit (cart clean-up, audit) is best-effort
 * and can never strand the claim or turn a created order into an error; and a
 * claim that finds its key's order already committed replays that order
 * rather than re-running into the {@code uq_order_idempotency_key} backstop.
 *
 * <h2>Stock invariants</h2>
 * Reservation is a single atomic UPDATE per line — the listing's, or the
 * option's for a listing with variants (V19) — all run by
 * {@link ListingStock#reserveAll} in listing-id order under its lock rule, so
 * two concurrent multi-line orders can never deadlock; a 0 update-count means
 * insufficient stock (or a just-deactivated listing) and aborts the order.
 * Release happens exactly once, guarded by {@code market_order.stock_released}
 * ({@link #releaseStockOnce}), back to wherever each line was reserved.
 */
@Slf4j
@Service
public class OrderService {

    /** S2S expiry-extension bounds (minutes) — mirrors the booking-service
     *  extend-hold contract: enough to outlive a payment code, never long
     *  enough to squat on stock. */
    static final int MIN_EXTEND_MINUTES = 1;
    static final int MAX_EXTEND_MINUTES = 60;

    /** {@link #refusalFor}'s safety-net message (422 {@code order_line_refused}):
     *  customer-safe, says what to do, names no field or endpoint. */
    static final String ORDER_LINE_REFUSED_MESSAGE =
            "Some items in this order cannot be bought as they are. Change or remove them and try again.";

    private final MarketOrderRepository orderRepository;
    private final MarketOrderItemRepository itemRepository;
    private final MarketOrderDeliveryFeeRepository deliveryFeeRepository;
    private final MarketOrderSellerRepository orderSellerRepository;
    private final ListingStock listingStock;
    private final OrderTransitionService transitions;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final MarketplaceMetrics metrics;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final CheckoutService checkoutService;
    private final CheckoutPricer pricer;
    private final CartService cartService;
    private final FulfilmentService fulfilmentService;
    private final SettlementService settlementService;
    private final OrderViewAssembler views;
    private final Msisdns msisdns;

    private final int maxItems;
    private final int maxQuantityPerItem;
    private final long paymentTtlMinutes;
    private final String currency;

    public OrderService(MarketOrderRepository orderRepository,
                        MarketOrderItemRepository itemRepository,
                        MarketOrderDeliveryFeeRepository deliveryFeeRepository,
                        MarketOrderSellerRepository orderSellerRepository,
                        ListingStock listingStock,
                        OrderTransitionService transitions,
                        IdempotencyService idempotencyService,
                        AuditService auditService,
                        MarketplaceMetrics metrics,
                        ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager,
                        CheckoutService checkoutService,
                        CheckoutPricer pricer,
                        CartService cartService,
                        FulfilmentService fulfilmentService,
                        SettlementService settlementService,
                        OrderViewAssembler views,
                        Msisdns msisdns,
                        @Value("${marketplace.order.max-items}") int maxItems,
                        @Value("${marketplace.order.max-quantity-per-item}") int maxQuantityPerItem,
                        @Value("${marketplace.order.payment-ttl-minutes}") long paymentTtlMinutes,
                        @Value("${innbucks.currency}") String currency) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.deliveryFeeRepository = deliveryFeeRepository;
        this.orderSellerRepository = orderSellerRepository;
        this.listingStock = listingStock;
        this.transitions = transitions;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.checkoutService = checkoutService;
        this.pricer = pricer;
        this.cartService = cartService;
        this.fulfilmentService = fulfilmentService;
        this.settlementService = settlementService;
        this.views = views;
        this.msisdns = msisdns;
        this.maxItems = maxItems;
        this.maxQuantityPerItem = maxQuantityPerItem;
        this.paymentTtlMinutes = paymentTtlMinutes;
        this.currency = currency;
    }

    // ------------------------------------------------------------------
    // Buyer surface
    // ------------------------------------------------------------------

    public OrderResponse createOrder(AuthenticatedUser buyer, CreateOrderRequest request,
                                     String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("idempotency_key_required",
                    "Idempotency-Key header is required");
        }
        // Namespaced per buyer: another caller reusing this buyer's raw key can
        // neither replay their response nor collide with their claim.
        String keyHash = IdempotencyService.namespaced(buyer.uuid(), idempotencyKey.trim());
        ClaimResult claim = idempotencyService.claim(keyHash, fingerprint(request));
        switch (claim) {
            case ClaimResult.Replay replay -> {
                return readStoredResponse(replay.responseBody());
            }
            case ClaimResult.InFlight inFlight -> throw ApiException.conflict("request_in_flight",
                    "A request with this Idempotency-Key is already in flight");
            case ClaimResult.Mismatch mismatch -> throw ApiException.unprocessable("idempotency_key_reuse",
                    "Idempotency-Key was already used with a different request body");
            case ClaimResult.New fresh -> { /* we own the claim — run the work */ }
        }

        // Defence in depth: a claim we own over a key whose order has ALREADY
        // committed (a stale-claim takeover after a crash between commit and
        // storing the replay body, or a store that itself failed) must replay
        // that order. Re-running would reserve stock again, hit the
        // uq_order_idempotency_key backstop, roll back and answer 500 - on
        // every retry, for good, while the real order sits unreachable.
        // Inside the try so a failed lookup still frees the claim.
        OrderResponse response;
        try {
            OrderResponse committed = replayCommittedOrder(buyer, keyHash);
            if (committed != null) {
                return committed;
            }
            response = transactionTemplate.execute(tx -> createOrderTx(buyer, request, keyHash));
        } catch (RuntimeException ex) {
            // A business refusal (ApiException) is thrown by our own code before
            // commit, so nothing persisted. Anything else may be a commit that
            // failed ambiguously, or a racing takeover that won the unique index:
            // if an order for this key DID commit, answer with it and keep the
            // claim (its fingerprint still guards the key) instead of freeing it.
            if (!(ex instanceof ApiException)) {
                OrderResponse racedIn = replayCommittedOrderQuietly(buyer, keyHash);
                if (racedIn != null) {
                    return racedIn;
                }
            }
            // Nothing persisted (the tx rolled back) - free the claim so a
            // retry with the same key can re-execute.
            idempotencyService.release(keyHash);
            throw ex;
        }

        // FIRST thing after the commit: store the replay body. From here the
        // order exists, so nothing below may leave the claim IN_PROGRESS -
        // a stranded claim answers 409 for a minute and then (before the
        // replay guard above) re-ran into the unique index on every retry.
        // First stored body wins: if a caller that took this claim over as
        // stale already stored this order, answer with THOSE bytes, so both
        // clients and every later replay agree.
        response = storeReplayBody(keyHash, response);

        // The ordered lines leave the cart only once the order has COMMITTED.
        // Clearing them inside the transaction and then rolling back would
        // look to the shopper like their cart vanished and their order failed;
        // losing this call leaves stale lines they can remove themselves,
        // which is strictly the better failure - so it is best-effort and can
        // never turn a created order into an error response. Anything they
        // did not check out stays where it was.
        if (request.sourcedFromCart()) {
            removeOrderedFromCart(buyer, response);
        }

        // Audit + metric AFTER the commit: AuditService commits in its own
        // REQUIRES_NEW tx, so recording inside ours would leave an audit row
        // for an order that then rolled back. (record() swallows its own
        // failures.)
        auditService.record(AuditEventType.ORDER_CREATED, buyer.uuid(),
                response.id().toString(), createdMetadata(response));
        metrics.orderOutcome("created");
        return response;
    }

    /**
     * Removes the checked-out lines from the buyer's cart, best-effort. Plain
     * lines leave {@code cart_item} exactly as before V19; option lines leave
     * {@code cart_variant_item} - touched only when the order had any. A
     * failure is logged by order reference (never the buyer's number) and
     * metered, and swallowed: the order has committed and its replay body is
     * stored, so the buyer must get it back.
     */
    private void removeOrderedFromCart(AuthenticatedUser buyer, OrderResponse response) {
        try {
            UUID buyerId = UUID.fromString(buyer.uuid());
            cartService.removeOrdered(buyerId, response.items().stream()
                    .filter(line -> line.variantId() == null)
                    .map(OrderResponse.Line::listingId).toList());
            List<UUID> orderedVariants = response.items().stream()
                    .map(OrderResponse.Line::variantId).filter(java.util.Objects::nonNull).toList();
            if (!orderedVariants.isEmpty()) {
                cartService.removeOrderedVariants(buyerId, orderedVariants);
            }
        } catch (RuntimeException ex) {
            metrics.orderPostCommitFailure("cart_cleanup");
            log.warn("order {} committed but its lines could not be removed from the cart "
                    + "(left for the buyer to remove): {}", response.orderRef(), ex.toString());
        }
    }

    /**
     * The order already committed under {@code keyHash}, rendered as it stands
     * now and stored as the claim's replay body, or null when there is none.
     *
     * <p>Rendered, not the original bytes: this path exists precisely because
     * the original body was never stored (the buyer saw an error, not an
     * order). Stored only while the claim is still in flight - if another
     * caller already stored a body for this key (a live-but-slow owner, or a
     * concurrent recovery), that body wins and is what this caller answers
     * with, so no replay ever changes under a client that already saw one.
     * Once stored, every later retry replays those bytes verbatim, like any
     * other replay. The cart is deliberately not touched - by now the buyer
     * may have re-added a line on purpose, and a stale line is theirs to
     * remove.
     *
     * <p>Audited as {@code ORDER_CREATE_RECOVERED}, not as a second
     * {@code ORDER_CREATED}: a JVM that died between the commit and the
     * post-commit steps wrote no creation audit at all, and this row is what
     * puts such an order on the tamper-evident chain; where the creation WAS
     * audited, it records that a retry was answered from the committed order.
     *
     * <p>The key is namespaced per buyer, so the order is the caller's by
     * construction; the owner check is a guard against that ever changing.
     */
    private OrderResponse replayCommittedOrder(AuthenticatedUser buyer, String keyHash) {
        MarketOrder order = orderRepository.findByIdempotencyKey(keyHash).orElse(null);
        if (order == null) {
            return null;
        }
        if (!order.getBuyerUuid().equals(UUID.fromString(buyer.uuid()))) {
            log.error("idempotency key {} resolves to order {} of another buyer - not replaying",
                    keyHash, order.getOrderRef());
            return null;
        }
        OrderResponse response = storeReplayBody(keyHash, views.toResponse(order));
        auditService.record(AuditEventType.ORDER_CREATE_RECOVERED, buyer.uuid(),
                order.getId().toString(), createdMetadata(response));
        metrics.orderIdempotentRecovery();
        // The key hash is not a secret (SHA-256 of scope + key) - safe to log.
        log.warn("idempotency key {} already has committed order {} - replaying it instead of "
                + "re-running the creation", keyHash, order.getOrderRef());
        return response;
    }

    /** {@link #replayCommittedOrder} for the failure path: a lookup that
     *  itself fails must never mask the exception being handled. */
    private OrderResponse replayCommittedOrderQuietly(AuthenticatedUser buyer, String keyHash) {
        try {
            return replayCommittedOrder(buyer, keyHash);
        } catch (RuntimeException lookupFailure) {
            log.warn("idempotency key {}: could not check for a committed order after a failed "
                    + "creation: {}", keyHash, lookupFailure.toString());
            return null;
        }
    }

    private OrderResponse createOrderTx(AuthenticatedUser buyer, CreateOrderRequest request,
                                        String keyHash) {
        // Where the lines come from is CheckoutService's question, answered the
        // same way for the quote and the order — a quote and the order made
        // from it must never price different baskets.
        List<BasketLine> basket = checkoutService.resolveBasket(buyer, request.sourcedFromCart(),
                request.items() == null ? List.of()
                        : request.items().stream()
                                .map(item -> new BasketLine(item.listingId(), item.quantity(),
                                        item.variantId()))
                                .toList());
        validateBasket(basket);

        String buyerMsisdn = msisdns.normalize(resolveBuyerMsisdn(buyer, request), "buyerMsisdn");
        DeliveryMethod requestedMethod = checkoutService.resolveMethod(request.deliveryMethod());
        // Load, then plan, then price (V20) - the quote's order of operations,
        // so a quote and the order made from it resolve the same basket the
        // same way.
        LoadedBasket loaded = pricer.load(basket);
        DeliveryPlan plan = checkoutService.resolvePlan(requestedMethod);
        // Resolved BEFORE any stock is touched: a buyer with no saved address
        // must be refused having reserved nothing. Only when some seller
        // delivers - a collection needs no destination.
        DeliveryAddress destination =
                checkoutService.resolveAddress(buyer, plan.summary(), request.deliveryAddressId());

        // Availability, pricing and the subtotal all come from the SAME
        // resolver the cart and the quote use, so the three screens a shopper
        // sees in a row cannot disagree about what is buyable. Still ADVISORY:
        // reserveStock below is the authoritative guard.
        // Each seller's method is part of the same resolution - a line nobody
        // delivers to the buyer's town, or a line to collect from a seller who
        // only delivers, is refused here, before any stock is held.
        PricedBasket priced = pricer.price(loaded, plan,
                destination == null ? null : destination.getTownCode());
        if (!priced.issues().isEmpty()) {
            throw refusalFor(priced.issues(), priced.sellers())
                    .withDetails(OrderRejectionDetails.of(priced.issues()));
        }
        // Where each collecting seller's goods are collected, by the same
        // resolver the quote used - and before any stock is held, so a choice
        // that is not the seller's refuses the order having reserved nothing.
        Map<UUID, CollectionPoint> collectionPoints = checkoutService.resolveCollectionPoints(
                priced, request.collectionPoints());

        long deliveryFee = priced.deliveryFeeCents();
        long totalCents;
        try {
            totalCents = Math.addExact(priced.subtotalCents(), deliveryFee);
        } catch (ArithmeticException ex) {
            throw ApiException.unprocessable("order_total_overflow",
                    "Order total exceeds the maximum representable amount");
        }

        reserveStock(priced);

        Instant now = Instant.now();
        MarketOrder order = MarketOrder.builder()
                .id(UUID.randomUUID())
                .orderRef(newOrderRef())
                .buyerUuid(UUID.fromString(buyer.uuid()))
                .buyerMsisdn(buyerMsisdn)
                .status(OrderStatus.PENDING_PAYMENT)
                .subtotalCents(priced.subtotalCents())
                .deliveryFeeCents(deliveryFee)
                .totalCents(totalCents)
                .currency(currency)
                // The order-level summary: DELIVERY when any seller delivers.
                // Equal to the requested method on every uniform plan.
                .deliveryMethod(plan.summary())
                .expiresAt(now.plus(paymentTtlMinutes, ChronoUnit.MINUTES))
                .stockReleased(false)
                .idempotencyKey(keyHash)
                .createdAt(now)
                .updatedAt(now)
                .build();
        applyDestination(order, plan.summary(), destination);
        applyRecipient(order, request.recipient());
        orderRepository.save(order);

        List<MarketOrderItem> items = priced.lines().stream()
                .map(line -> MarketOrderItem.builder()
                        .id(UUID.randomUUID())
                        .orderId(order.getId())
                        .listingId(line.listingId())
                        // SNAPSHOT, not a later join back to the listing: the
                        // seller who must pack this and be paid for it is the
                        // one who was selling at order time.
                        .merchantId(line.listing().getMerchantId())
                        .titleSnapshot(line.listing().getTitle())
                        .unitPriceCents(line.unitPriceCents())
                        .quantity(line.quantity())
                        .lineTotalCents(line.lineTotalCents())
                        // V19: the option AS NAMED NOW - a later rename or
                        // removal changes nothing about what was bought.
                        .variantId(line.variant() == null ? null : line.variant().getId())
                        .variantLabel(line.variantLabel())
                        .build())
                .toList();
        itemRepository.saveAll(items);
        // Each seller's method, recorded now for EVERY seller on the order
        // (V20): what each parcel copies when the order is paid, and the only
        // per-seller record that exists before there are parcels. Keyed on the
        // items' own merchant snapshot, so every parcel the payment opens has
        // its row.
        orderSellerRepository.saveAll(items.stream()
                .map(MarketOrderItem::getMerchantId)
                .distinct()
                .map(merchantId -> new MarketOrderSeller(order.getId(), merchantId,
                        plan.methodFor(merchantId)))
                .toList());
        // Each DELIVERING seller's fee, fixed now: the buyer pays what they were
        // quoted even if the seller reprices a town before the order is paid.
        // A collecting seller has no row.
        if (!priced.deliveryFeesByMerchant().isEmpty()) {
            deliveryFeeRepository.saveAll(priced.deliveryFeesByMerchant().entrySet().stream()
                    .map(e -> new MarketOrderDeliveryFee(order.getId(), e.getKey(), e.getValue()))
                    .toList());
        }
        // Each collecting seller's collection point, COPIED now: a seller who
        // later moves or removes the point must not move goods the buyer was
        // told to fetch.
        checkoutService.recordCollectionPoints(order.getId(), collectionPoints);
        transitions.journalCreation(order);
        log.info("order created id={} ref={} lines={} subtotalCents={} deliveryFeeCents={} "
                        + "totalCents={} delivery={}",
                order.getId(), order.getOrderRef(), items.size(), priced.subtotalCents(),
                deliveryFee, totalCents, plan.summary());
        return views.toResponse(order, items);
    }

    /**
     * Shape checks that do not need the catalogue: line count, per-line
     * quantity and duplicates. Run before anything is read or reserved so a
     * pathological order aborts cheaply.
     */
    private void validateBasket(List<BasketLine> basket) {
        if (basket.isEmpty() || basket.size() > maxItems) {
            throw ApiException.badRequest("invalid_items",
                    "Order must contain between 1 and " + maxItems + " line items");
        }
        Set<LineKey> seen = new HashSet<>();
        for (BasketLine line : basket) {
            if (line.listingId() == null) {
                // Bean Validation already rejects these; defensive for direct callers.
                throw ApiException.badRequest("invalid_items", "Each line needs listingId and quantity");
            }
            if (line.quantity() < 1 || line.quantity() > maxQuantityPerItem) {
                throw ApiException.badRequest("invalid_quantity",
                        "Quantity for listing " + line.listingId() + " must be between 1 and "
                                + maxQuantityPerItem);
            }
            // V19: a line is (listing, option). Two sizes of one listing are
            // two lines; the same size twice is the duplicate.
            if (!seen.add(line.key())) {
                throw ApiException.badRequest("duplicate_listing", line.variantId() == null
                        ? "Listing " + line.listingId() + " appears more than once in the order"
                        : "Listing " + line.listingId() + " variant " + line.variantId()
                                + " appears more than once in the order");
            }
        }
    }

    /**
     * Copies the chosen address ONTO the order. A snapshot, never a reference:
     * the buyer may rename, edit or delete the book entry the moment after
     * ordering, and a parcel already packed must not change destination — nor
     * lose one — because of it.
     */
    /**
     * Copies the gift recipient onto the order, if one was named.
     *
     * <p>Nothing is defaulted: an order with no {@code recipient} block is
     * bought for the buyer, and filling these columns with the buyer's own
     * details would assert a gift nobody sent — every downstream surface reads
     * "is there a recipient" as "is this a gift".
     *
     * <p>The number goes through the SAME {@link Msisdns} as the payer's and
     * the courier's, because a number this service will actually message must
     * mean the same thing on every surface that stores one. The name is
     * sanitized and then re-checked for emptiness: Bean Validation rejects a
     * blank name, but a name that is nothing BUT markup survives that and
     * arrives here empty.
     */
    private void applyRecipient(MarketOrder order, CreateOrderRequest.Recipient recipient) {
        if (recipient == null) {
            return;
        }
        String name = blankToNull(TextSanitizer.sanitize(recipient.name()));
        if (name == null) {
            throw ApiException.badRequest("recipient_name_required",
                    "recipient.name is required when an order names a recipient");
        }
        order.setRecipientName(name);
        if (recipient.msisdn() != null && !recipient.msisdn().isBlank()) {
            order.setRecipientMsisdn(msisdns.normalize(recipient.msisdn(), "recipient.msisdn"));
        }
        order.setGiftMessage(blankToNull(TextSanitizer.sanitize(recipient.message())));
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void applyDestination(MarketOrder order, DeliveryMethod summary,
                                         DeliveryAddress address) {
        // A destination exactly when some seller delivers: the V9 CHECK refuses
        // a DELIVERY order without one, and a COLLECTION order carrying one
        // would show the seller an address nobody is shipping to.
        if ((address != null) != (summary == DeliveryMethod.DELIVERY)) {
            throw new IllegalStateException("Order destination does not match its delivery method "
                    + summary);
        }
        if (address == null) {
            return; // COLLECTION — no destination by construction
        }
        order.setDeliveryAddressId(address.getId());
        order.setDeliveryRecipientName(address.getRecipientName());
        order.setDeliveryRecipientMsisdn(address.getRecipientMsisdn());
        order.setDeliveryLine1(address.getLine1());
        order.setDeliveryLine2(address.getLine2());
        order.setDeliveryCity(address.getCity());
        order.setDeliveryArea(address.getArea());
        order.setDeliveryTownCode(address.getTownCode());
        order.setDeliveryLandmark(address.getLandmark());
    }

    /**
     * The refusal a rejected order reports at the TOP level, chosen to be
     * byte-identical to what the old abort-at-first-failure code produced —
     * only the {@code details} payload is new, so no existing client sees a
     * changed status, code or message.
     *
     * <p>Reproducing that exactly means reproducing two incidental orderings:
     * <ul>
     *   <li><b>Availability beat stock</b>, whatever their positions: every
     *       line's availability was checked before any stock was touched, so
     *       an unavailable line always threw first.</li>
     *   <li><b>Within stock failures, the smallest listing id won</b> — not the
     *       first in request order. {@code reserveStock} iterates sorted by id
     *       (deadlock avoidance), and it was the thrower.</li>
     * </ul>
     * Every newer reason ranks after every older one, so a refusal that also
     * has an older reason still headlines it: V14's {@code not_delivered_to_town},
     * then V19's {@code variant_unavailable} and {@code variant_required}, then
     * V20's {@code collection_not_offered}. Behind them a safety net answers any
     * reason this ranking does not know with 422 {@code order_line_refused}, so a
     * reason added later can never surface as a 500.
     * The {@code rejections} list itself stays in REQUEST order, which is what
     * a client renders.
     *
     * @param sellers each seller's share of the pricing - the
     *                {@code not_delivered_to_town} remedy offers collection only
     *                when that seller collects
     */
    static ApiException refusalFor(List<OrderLineRejection> rejections,
                                   Map<UUID, SellerPricing> sellers) {
        return rejections.stream()
                .filter(r -> OrderLineRejection.REASON_UNAVAILABLE.equals(r.reason()))
                .findFirst()
                .map(r -> ApiException.unprocessable("listing_unavailable",
                        "Listing " + r.listingId() + " is not available"))
                .or(() -> rejections.stream()
                        .filter(r -> OrderLineRejection.REASON_INSUFFICIENT_STOCK.equals(r.reason()))
                        // The reserve's own order: by listing, then by option
                        // with a plain line first.
                        .min(Comparator.comparing(OrderLineRejection::listingId)
                                .thenComparing(OrderLineRejection::variantId,
                                        Comparator.nullsFirst(Comparator.naturalOrder())))
                        .map(r -> ApiException.conflict("insufficient_stock",
                                insufficientStockMessage(r.listingId(), r.variantId()))))
                // V14: a line nobody delivers to the buyer's town. Ranked after
                // availability and stock so every pre-V14 refusal stays
                // byte-identical; the details name every line either way. The
                // remedy names collection only when THAT seller offers it (V20)
                // - otherwise the pre-V20 text, byte for byte.
                .or(() -> rejections.stream()
                        .filter(r -> OrderLineRejection.REASON_NOT_DELIVERED_TO_TOWN.equals(r.reason()))
                        .findFirst()
                        .map(r -> ApiException.unprocessable("not_delivered_to_town",
                                r.message() + (collects(sellers, r.merchantId())
                                        ? ". Choose collection or another address."
                                        : ". Choose another address or remove it."))))
                // V19, after everything older so every earlier refusal stays
                // byte-identical: a named option that is gone, then a line that
                // named none on a listing that sells options.
                .or(() -> rejections.stream()
                        .filter(r -> OrderLineRejection.REASON_VARIANT_UNAVAILABLE.equals(r.reason()))
                        .findFirst()
                        .map(r -> ApiException.unprocessable("variant_unavailable",
                                "Listing " + r.listingId() + " variant " + r.variantId()
                                        + " is not available")))
                .or(() -> rejections.stream()
                        .filter(r -> OrderLineRejection.REASON_VARIANT_REQUIRED.equals(r.reason()))
                        .findFirst()
                        .map(r -> ApiException.unprocessable("variant_required",
                                "Listing " + r.listingId() + " needs an option chosen")))
                // V20, last: a line to collect from a seller who only delivers.
                .or(() -> rejections.stream()
                        .filter(r -> OrderLineRejection.REASON_COLLECTION_NOT_OFFERED.equals(r.reason()))
                        .findFirst()
                        .map(r -> ApiException.unprocessable("collection_not_offered",
                                r.message() + ". Choose delivery or remove it.")))
                // Safety net: a reason nothing above ranks. Refused like any
                // other line problem - never an IllegalStateException, which
                // would answer a shopper's fixable basket with a 500.
                .orElseGet(() -> ApiException.unprocessable("order_line_refused",
                        ORDER_LINE_REFUSED_MESSAGE));
    }

    /** Whether the seller offers collection; a seller the pricing does not name
     *  reads as collecting, which every seller did before V20. */
    private static boolean collects(Map<UUID, SellerPricing> sellers, UUID merchantId) {
        SellerPricing seller = merchantId == null ? null : sellers.get(merchantId);
        return seller == null || seller.collectionOffered();
    }

    /**
     * Reserves every priced line through {@link ListingStock} — per listing in
     * UUID order (deadlock avoidance), each line by its own guarded UPDATE: the
     * listing's for a listing without options (exactly the pre-V19
     * statement), the option's for one with. A line that cannot be reserved
     * (insufficient stock, or no longer ACTIVE) un-reserves everything taken
     * and aborts with 409.
     */
    private void reserveStock(PricedBasket priced) {
        StockLine refused = listingStock.reserveAll(priced.lines().stream()
                .map(line -> new StockLine(line.listingId(),
                        line.variant() == null ? null : line.variant().getId(), line.quantity()))
                .toList());
        if (refused != null) {
            throw ApiException.conflict("insufficient_stock",
                    insufficientStockMessage(refused.listingId(), refused.variantId()));
        }
    }

    /** "Insufficient stock for listing X" — exactly the pre-V19 text for a
     *  line without an option — or "... variant V" for one with. */
    private static String insufficientStockMessage(UUID listingId, UUID variantId) {
        return variantId == null
                ? "Insufficient stock for listing " + listingId
                : "Insufficient stock for listing " + listingId + " variant " + variantId;
    }

    @Transactional
    public OrderResponse cancelOrder(AuthenticatedUser buyer, UUID orderId) {
        MarketOrder order = requireOwn(buyer, orderId);
        // Only PENDING_PAYMENT may cancel — the state machine refuses (409)
        // everything else, terminals included.
        transitions.transition(order, OrderStatus.CANCELLED, "Cancelled by buyer");
        releaseStockOnce(order);
        return views.toResponse(order);
    }

    /**
     * The buyer confirms a parcel arrived — the last step of the journey.
     *
     * <p>Lives on the ORDER rather than the fulfilment resource because that is
     * where a shopper looks: they think in orders, not parcels. Returns the
     * whole order so the app re-renders the tracking screen from one response.
     */
    @Transactional
    public OrderResponse confirmReceived(AuthenticatedUser buyer, UUID orderId, UUID fulfilmentId) {
        MarketOrder order = requireOwn(buyer, orderId);
        // Checked BEFORE anything moves (as cancelParcel does): a parcel that is
        // the buyer's but on a DIFFERENT order of theirs is the same 404, or the
        // app would show the wrong order closing. Checking after the close left
        // a FULFILMENT_DELIVERED audit row behind — the audit commits in its own
        // transaction and survived the rollback.
        requireParcelOnOrder(order, fulfilmentId);
        fulfilmentService.confirmReceived(buyer, fulfilmentId);
        return views.toResponse(order);
    }

    /**
     * The buyer cancels one parcel of a PAID order before the seller sends it
     * (V16). Per parcel, like {@link #confirmReceived}: a multi-seller order
     * ships one seller at a time, so it is called off one seller at a time.
     * The whole order comes back so the app re-renders from one response.
     */
    @Transactional
    public OrderResponse cancelParcel(AuthenticatedUser buyer, UUID orderId, UUID fulfilmentId,
                                      String reason) {
        MarketOrder order = requireOwn(buyer, orderId);
        // Checked BEFORE anything moves: another order's parcel — even one of
        // the buyer's own — is the same 404, or the app would show the wrong
        // order changing.
        requireParcelOnOrder(order, fulfilmentId);
        fulfilmentService.cancelByBuyer(buyer, fulfilmentId, reason);
        return views.toResponse(order);
    }

    private void requireParcelOnOrder(MarketOrder order, UUID fulfilmentId) {
        boolean onThisOrder = fulfilmentService.forOrder(order.getId()).stream()
                .anyMatch(p -> p.getId().equals(fulfilmentId));
        if (!onThisOrder) {
            throw ApiException.notFound("fulfilment_not_found", "Fulfilment not found");
        }
    }

    @NameResolvingRead
    public Page<OrderResponse> getMine(AuthenticatedUser buyer, Pageable pageable) {
        return withItems(orderRepository.findByBuyerUuid(UUID.fromString(buyer.uuid()), pageable));
    }

    /**
     * SUPER_ADMIN oversight read: EVERY buyer's orders, newest-first per the
     * controller's pageable, optionally narrowed to one buyer. Role gating is
     * the controller's {@code @PreAuthorize}; nothing here is owner-scoped.
     */
    @NameResolvingRead
    public Page<OrderResponse> getAll(UUID buyerUuidFilter, Pageable pageable) {
        Page<MarketOrder> page = buyerUuidFilter == null
                ? orderRepository.findAll(pageable)
                : orderRepository.findByBuyerUuid(buyerUuidFilter, pageable);
        return withItems(page);
    }

    /**
     * Single-order read. CUSTOMER callers stay owner-masked (an order that
     * exists but belongs to someone else is the same 404 as a nonexistent id);
     * SUPER_ADMIN reads ANY order by id — fleet oversight, so no masking.
     */
    @NameResolvingRead
    public OrderResponse getOrder(AuthenticatedUser caller, UUID orderId) {
        MarketOrder order = caller.isSuperAdmin()
                ? orderRepository.findById(orderId)
                        .orElseThrow(() -> ApiException.notFound("order_not_found", "Order not found"))
                : requireOwn(caller, orderId);
        return views.toResponse(order);
    }

    /** Page assembly — lines, parcels and seller names each cost ONE query for
     *  the whole page (no N+1 on the screen a shopper opens most). */
    private Page<OrderResponse> withItems(Page<MarketOrder> page) {
        return views.toResponsePage(page);
    }

    // ------------------------------------------------------------------
    // Internal S2S surface (payments) — keyed by orderRef, never by id
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public InternalOrderView getByRef(String orderRef) {
        return toView(requireByRef(orderRef));
    }

    /**
     * Payments extends the stock hold to outlive the payment code it is about
     * to mint (the booking-service extend-hold rationale). Never shortens; not
     * a state change, so it bypasses the state machine but still journals.
     */
    @Transactional
    public InternalOrderView extendExpiry(String orderRef, Integer minutes) {
        if (minutes == null || minutes < MIN_EXTEND_MINUTES || minutes > MAX_EXTEND_MINUTES) {
            throw ApiException.badRequest("invalid_extension",
                    "minutes must be between " + MIN_EXTEND_MINUTES + " and " + MAX_EXTEND_MINUTES);
        }
        MarketOrder order = requireByRef(orderRef);
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw ApiException.conflict("order_not_extendable",
                    "Order " + orderRef + " is not awaiting payment");
        }
        Instant candidate = Instant.now().plus(minutes, ChronoUnit.MINUTES);
        if (candidate.isAfter(order.getExpiresAt())) {
            order.setExpiresAt(candidate);
        }
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);
        transitions.note(order, "Expiry extended by " + minutes + "m to " + order.getExpiresAt()
                + " (S2S)");
        return toView(order);
    }

    /**
     * Payment confirmation, idempotent by {@code paymentRef}. The amount
     * cross-check is the 100x guard: a mismatch NEVER confirms — it audits,
     * counts, and parks the order for the operator, exactly like the
     * ticketing payment rail.
     */
    @Transactional
    public InternalOrderView confirmPayment(String orderRef, ConfirmPaymentRequest request) {
        MarketOrder order = requireByRef(orderRef);
        if (order.getStatus() == OrderStatus.PAID) {
            if (request.paymentRef().equals(order.getPaymentRef())) {
                return toView(order); // replayed confirm — same outcome, no change
            }
            throw ApiException.conflict("order_already_paid",
                    "Order " + orderRef + " is already paid with a different payment reference");
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw ApiException.conflict("order_not_confirmable",
                    "Order " + orderRef + " is " + order.getStatus() + " and cannot be confirmed");
        }
        if (request.amountCents() != order.getTotalCents()) {
            metrics.confirmMismatch();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("orderRef", order.getOrderRef());
            metadata.put("expectedCents", order.getTotalCents());
            metadata.put("receivedCents", request.amountCents());
            metadata.put("paymentRef", request.paymentRef());
            auditService.record(AuditEventType.ORDER_CONFIRM_AMOUNT_MISMATCH, "system",
                    order.getId().toString(), metadata);
            throw ApiException.unprocessable("amount_mismatch",
                    "Paid amount " + request.amountCents() + " does not match order total "
                            + order.getTotalCents());
        }
        order.setPaymentRef(request.paymentRef());
        order.setPaidAt(Instant.now());
        transitions.transition(order, OrderStatus.PAID,
                "Payment confirmed by platform payments service");
        // Parcels open in the SAME transaction as the PAID transition. An order
        // that committed as paid with nothing on any seller's queue would be
        // money taken for goods nobody was ever asked to send — and nothing
        // would notice, because that queue is the only place it would show.
        // Idempotent, so a replayed confirm cannot double a seller's work.
        fulfilmentService.openForOrder(order);
        // And the escrow beside them: one HELD settlement per parcel, same
        // transaction, same idempotency — money recorded as collected with no
        // ledger row saying whose it is would be the state V10 exists to
        // make impossible.
        settlementService.openForOrder(order);
        return toView(order);
    }

    // ------------------------------------------------------------------
    // Expiry (called per row by OrderExpirySweeper)
    // ------------------------------------------------------------------

    /**
     * Expires ONE lapsed order in its own transaction. Re-checks state after
     * the sweep query (a confirm or extension may have landed in between) and
     * uses the skip-silently transition so losing that race never aborts the
     * sweep. Returns whether this call expired the order.
     */
    @Transactional
    public boolean expireOne(UUID orderId) {
        MarketOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return false;
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT
                || order.getExpiresAt().isAfter(Instant.now())) {
            return false;
        }
        if (!transitions.transitionIfLegal(order, OrderStatus.EXPIRED,
                "Payment TTL lapsed; stock released by expiry sweep")) {
            return false;
        }
        releaseStockOnce(order);
        return true;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Returns reserved stock to the listings, EXACTLY ONCE: the
     * {@code stock_released} flag is the double-release guard shared by cancel
     * and expiry — a no-op when already released. Runs inside the caller's
     * transaction, so the flag flip and the restocks commit atomically; a
     * concurrent cancel/expiry of the same order is serialised by the
     * {@code @Version} optimistic lock on the status transition that precedes
     * every call to this method.
     */
    private void releaseStockOnce(MarketOrder order) {
        if (order.isStockReleased()) {
            return;
        }
        // ListingStock returns each line to where it was reserved (the listing,
        // or the option), in the reserve's lock order, and publishes
        // ListingRestocked for a listing it brings back from 0. The AFTER_COMMIT
        // listener only fires if this cancel/expiry actually commits.
        listingStock.returnAll(itemRepository.findByOrderId(order.getId()).stream()
                .map(item -> new StockLine(item.getListingId(), item.getVariantId(),
                        item.getQuantity()))
                .toList());
        order.setStockReleased(true);
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);
    }

    private MarketOrder requireOwn(AuthenticatedUser buyer, UUID orderId) {
        // Owner scoping in the query: not-yours and not-found are the same 404.
        return orderRepository.findByIdAndBuyerUuid(orderId, UUID.fromString(buyer.uuid()))
                .orElseThrow(() -> ApiException.notFound("order_not_found", "Order not found"));
    }

    private MarketOrder requireByRef(String orderRef) {
        return orderRepository.findByOrderRef(orderRef)
                .orElseThrow(() -> ApiException.notFound("order_not_found", "Order not found"));
    }

    private String newOrderRef() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String ref = OrderRefs.newRef();
            if (!orderRepository.existsByOrderRef(ref)) {
                return ref;
            }
        }
        // 5 straight 48-bit collisions — something is broken; uq_order_ref
        // stays the arbiter either way.
        throw new IllegalStateException("Could not allocate a unique order ref");
    }

    /**
     * The payer's number is the CALLER's number. The JWT's {@code phoneNumber}
     * claim wins whenever the token carries one; the body's {@code buyerMsisdn}
     * is read only for a token without a phone (staff-shaped or legacy).
     *
     * <p>Why the claim must win: this value is handed to the payments service
     * as the payer, and on the EcoCash rail it is the phone that receives the
     * PIN prompt. Read from the body alone, any authenticated buyer could have
     * a "pay $X" prompt pushed to any number they typed. The same defect was
     * fixed on payment-service's shop-checkout, whose {@code msisdn} field is
     * now deprecated and ignored for exactly this reason.
     */
    private static String resolveBuyerMsisdn(AuthenticatedUser buyer, CreateOrderRequest request) {
        if (buyer.phone() != null && !buyer.phone().isBlank()) {
            return buyer.phone();
        }
        return request.buyerMsisdn();
    }

    /** Canonical request fingerprint for replay-vs-mismatch: SHA-256 over the
     *  Jackson serialization (record component order is stable). */
    private String fingerprint(CreateOrderRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(objectMapper.writeValueAsBytes(request)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to fingerprint order request", ex);
        }
    }

    /**
     * Stores {@code response} as the claim's replay body and returns what the
     * caller must answer with: {@code response} itself, or - when another
     * holder of this key's claim already stored a body - that stored body
     * (first stored wins; see {@link IdempotencyService#completeIfInFlight}).
     */
    private OrderResponse storeReplayBody(String keyHash, OrderResponse response) {
        try {
            return idempotencyService.completeIfInFlight(keyHash, HttpStatus.CREATED.value(),
                            objectMapper.writeValueAsString(response))
                    .map(this::readStoredResponse)
                    .orElse(response);
        } catch (JsonProcessingException | RuntimeException ex) {
            // The order is committed and will be returned regardless. A lost
            // replay body leaves the claim IN_PROGRESS: a same-key retry gets
            // 409 until the claim goes stale, then takes it over and
            // replayCommittedOrder answers with this order (never a re-run).
            metrics.orderPostCommitFailure("replay_store");
            log.error("Failed to store idempotent replay body for order {}", response.orderRef(), ex);
            return response;
        }
    }

    private OrderResponse readStoredResponse(String body) {
        try {
            return objectMapper.readValue(body, OrderResponse.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored idempotent order response is unreadable", ex);
        }
    }

    private Map<String, Object> createdMetadata(OrderResponse response) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderRef", response.orderRef());
        metadata.put("totalCents", response.totalCents());
        metadata.put("currency", response.currency());
        metadata.put("lineCount", response.items().size());
        return metadata;
    }

    private static InternalOrderView toView(MarketOrder order) {
        return new InternalOrderView(order.getOrderRef(), order.getStatus(), order.getTotalCents(),
                order.getCurrency(), order.getBuyerMsisdn(), order.getExpiresAt());
    }
}
