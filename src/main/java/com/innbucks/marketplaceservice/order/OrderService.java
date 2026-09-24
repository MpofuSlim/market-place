package com.innbucks.marketplaceservice.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.cart.CartService;
import com.innbucks.marketplaceservice.catalog.ListingRestocked;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.checkout.BasketLine;
import com.innbucks.marketplaceservice.checkout.CheckoutPricer;
import com.innbucks.marketplaceservice.checkout.CheckoutService;
import com.innbucks.marketplaceservice.checkout.PricedBasket;
import com.innbucks.marketplaceservice.delivery.DeliveryAddress;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentService;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.idempotency.ClaimResult;
import com.innbucks.marketplaceservice.idempotency.IdempotencyService;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.dto.ConfirmPaymentRequest;
import com.innbucks.marketplaceservice.order.dto.CreateOrderRequest;
import com.innbucks.marketplaceservice.pickup.CollectionPoint;
import com.innbucks.marketplaceservice.order.dto.InternalOrderView;
import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;
import com.innbucks.marketplaceservice.order.dto.OrderRejectionDetails;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.settlement.SettlementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
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
 * response only after that transaction commits, and released if it throws.
 *
 * <h2>Stock invariants</h2>
 * Reservation is a single atomic UPDATE per line
 * ({@link ListingRepository#reserveStock}), executed in listing-id order so
 * two concurrent multi-line orders can never deadlock; a 0 update-count means
 * insufficient stock (or a just-deactivated listing) and aborts the order.
 * Release happens exactly once, guarded by {@code market_order.stock_released}
 * ({@link #releaseStockOnce}).
 */
@Slf4j
@Service
public class OrderService {

    /** S2S expiry-extension bounds (minutes) — mirrors the booking-service
     *  extend-hold contract: enough to outlive a payment code, never long
     *  enough to squat on stock. */
    static final int MIN_EXTEND_MINUTES = 1;
    static final int MAX_EXTEND_MINUTES = 60;

    private final MarketOrderRepository orderRepository;
    private final MarketOrderItemRepository itemRepository;
    private final MarketOrderDeliveryFeeRepository deliveryFeeRepository;
    private final ListingRepository listingRepository;
    private final OrderTransitionService transitions;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final MarketplaceMetrics metrics;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
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
                        ListingRepository listingRepository,
                        OrderTransitionService transitions,
                        IdempotencyService idempotencyService,
                        AuditService auditService,
                        MarketplaceMetrics metrics,
                        ObjectMapper objectMapper,
                        ApplicationEventPublisher eventPublisher,
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
        this.listingRepository = listingRepository;
        this.transitions = transitions;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
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

        OrderResponse response;
        try {
            response = transactionTemplate.execute(tx -> createOrderTx(buyer, request, keyHash));
        } catch (RuntimeException ex) {
            // Nothing persisted (the tx rolled back) — free the claim so a
            // retry with the same key can re-execute.
            idempotencyService.release(keyHash);
            throw ex;
        }

        // The ordered lines leave the cart only once the order has COMMITTED.
        // Clearing them inside the transaction and then rolling back would
        // look to the shopper like their cart vanished and their order failed;
        // losing this call to a crash leaves stale lines they can remove
        // themselves, which is strictly the better failure. Anything they did
        // not check out stays where it was.
        if (request.sourcedFromCart()) {
            cartService.removeOrdered(UUID.fromString(buyer.uuid()),
                    response.items().stream().map(OrderResponse.Line::listingId).toList());
        }

        storeReplayBody(keyHash, response);
        // Audit + metric AFTER the commit: AuditService commits in its own
        // REQUIRES_NEW tx, so recording inside ours would leave an audit row
        // for an order that then rolled back.
        auditService.record(AuditEventType.ORDER_CREATED, buyer.uuid(),
                response.id().toString(), createdMetadata(response));
        metrics.orderOutcome("created");
        return response;
    }

    private OrderResponse createOrderTx(AuthenticatedUser buyer, CreateOrderRequest request,
                                        String keyHash) {
        // Where the lines come from is CheckoutService's question, answered the
        // same way for the quote and the order — a quote and the order made
        // from it must never price different baskets.
        List<BasketLine> basket = checkoutService.resolveBasket(buyer, request.sourcedFromCart(),
                request.items() == null ? List.of()
                        : request.items().stream()
                                .map(item -> new BasketLine(item.listingId(), item.quantity()))
                                .toList());
        validateBasket(basket);

        String buyerMsisdn = msisdns.normalize(resolveBuyerMsisdn(buyer, request), "buyerMsisdn");
        DeliveryMethod deliveryMethod = checkoutService.resolveMethod(request.deliveryMethod());
        // Resolved BEFORE any stock is touched: a buyer with no saved address
        // must be refused having reserved nothing.
        DeliveryAddress destination =
                checkoutService.resolveAddress(buyer, deliveryMethod, request.deliveryAddressId());

        // Availability, pricing and the subtotal all come from the SAME
        // resolver the cart and the quote use, so the three screens a shopper
        // sees in a row cannot disagree about what is buyable. Still ADVISORY:
        // reserveStock below is the authoritative guard.
        // Delivery coverage and each seller's fee to the buyer's town are part
        // of the same resolution - a line nobody delivers there is refused
        // here, before any stock is held.
        PricedBasket priced = pricer.price(basket, deliveryMethod,
                destination == null ? null : destination.getTownCode());
        if (!priced.issues().isEmpty()) {
            throw refusalFor(priced.issues()).withDetails(OrderRejectionDetails.of(priced.issues()));
        }
        // Where each seller's goods are collected (COLLECTION only), by the
        // same resolver the quote used - and before any stock is held, so a
        // choice that is not the seller's refuses the order having reserved
        // nothing.
        Map<UUID, CollectionPoint> collectionPoints = checkoutService.resolveCollectionPoints(
                deliveryMethod, priced, request.collectionPoints());

        long deliveryFee = priced.deliveryFeeCents();
        long totalCents;
        try {
            totalCents = Math.addExact(priced.subtotalCents(), deliveryFee);
        } catch (ArithmeticException ex) {
            throw ApiException.unprocessable("order_total_overflow",
                    "Order total exceeds the maximum representable amount");
        }

        reserveStock(basket);

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
                .deliveryMethod(deliveryMethod)
                .expiresAt(now.plus(paymentTtlMinutes, ChronoUnit.MINUTES))
                .stockReleased(false)
                .idempotencyKey(keyHash)
                .createdAt(now)
                .updatedAt(now)
                .build();
        applyDestination(order, destination);
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
                        .build())
                .toList();
        itemRepository.saveAll(items);
        // Each seller's fee, fixed now: the buyer pays what they were quoted
        // even if the seller reprices a town before the order is paid.
        if (!priced.deliveryFeesByMerchant().isEmpty()) {
            deliveryFeeRepository.saveAll(priced.deliveryFeesByMerchant().entrySet().stream()
                    .map(e -> new MarketOrderDeliveryFee(order.getId(), e.getKey(), e.getValue()))
                    .toList());
        }
        // Each seller's collection point, COPIED now: a seller who later moves
        // or removes the point must not move goods the buyer was told to fetch.
        checkoutService.recordCollectionPoints(order.getId(), collectionPoints);
        transitions.journalCreation(order);
        log.info("order created id={} ref={} lines={} subtotalCents={} deliveryFeeCents={} "
                        + "totalCents={} delivery={}",
                order.getId(), order.getOrderRef(), items.size(), priced.subtotalCents(),
                deliveryFee, totalCents, deliveryMethod);
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
        Set<UUID> seen = new HashSet<>();
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
            if (!seen.add(line.listingId())) {
                throw ApiException.badRequest("duplicate_listing",
                        "Listing " + line.listingId() + " appears more than once in the order");
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

    private static void applyDestination(MarketOrder order, DeliveryAddress address) {
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
     * The {@code rejections} list itself stays in REQUEST order, which is what
     * a client renders.
     */
    private static ApiException refusalFor(List<OrderLineRejection> rejections) {
        return rejections.stream()
                .filter(r -> OrderLineRejection.REASON_UNAVAILABLE.equals(r.reason()))
                .findFirst()
                .map(r -> ApiException.unprocessable("listing_unavailable",
                        "Listing " + r.listingId() + " is not available"))
                .or(() -> rejections.stream()
                        .filter(r -> OrderLineRejection.REASON_INSUFFICIENT_STOCK.equals(r.reason()))
                        .min(Comparator.comparing(OrderLineRejection::listingId))
                        .map(r -> ApiException.conflict("insufficient_stock",
                                "Insufficient stock for listing " + r.listingId())))
                // V14, last: a line nobody delivers to the buyer's town. Ranked
                // after availability and stock so every pre-V14 refusal stays
                // byte-identical; the details name every line either way.
                .or(() -> rejections.stream()
                        .filter(r -> OrderLineRejection.REASON_NOT_DELIVERED_TO_TOWN.equals(r.reason()))
                        .findFirst()
                        .map(r -> ApiException.unprocessable("not_delivered_to_town",
                                r.message() + ". Choose collection or another address.")))
                .orElseThrow(() -> new IllegalStateException(
                        "refusalFor called with no rejections"));
    }

    /**
     * Reserves every line atomically, SORTED BY LISTING ID so two concurrent
     * orders over the same listings always lock in the same sequence (deadlock
     * avoidance). A 0 update-count (insufficient stock or listing no longer
     * ACTIVE) restocks everything already reserved and aborts with 409. The
     * explicit restock keeps the guarantee even if this loop ever moves
     * outside the creating transaction; today the rollback would also undo it.
     */
    private void reserveStock(List<BasketLine> items) {
        List<BasketLine> sorted = items.stream()
                .sorted(Comparator.comparing(BasketLine::listingId))
                .toList();
        List<BasketLine> reserved = new ArrayList<>(sorted.size());
        for (BasketLine item : sorted) {
            if (listingRepository.reserveStock(item.listingId(), item.quantity()) == 0) {
                for (BasketLine taken : reserved) {
                    listingRepository.restock(taken.listingId(), taken.quantity());
                }
                throw ApiException.conflict("insufficient_stock",
                        "Insufficient stock for listing " + item.listingId());
            }
            reserved.add(item);
        }
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
        OrderFulfilment parcel = fulfilmentService.confirmReceived(buyer, fulfilmentId);
        if (!parcel.getOrderId().equals(order.getId())) {
            // The parcel is the buyer's but belongs to a DIFFERENT order of
            // theirs. Refused rather than quietly confirmed, because the app
            // would then show the wrong order closing.
            throw ApiException.notFound("fulfilment_not_found", "Fulfilment not found");
        }
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
        boolean onThisOrder = fulfilmentService.forOrder(order.getId()).stream()
                .anyMatch(p -> p.getId().equals(fulfilmentId));
        if (!onThisOrder) {
            throw ApiException.notFound("fulfilment_not_found", "Fulfilment not found");
        }
        fulfilmentService.cancelByBuyer(buyer, fulfilmentId, reason);
        return views.toResponse(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getMine(AuthenticatedUser buyer, Pageable pageable) {
        return withItems(orderRepository.findByBuyerUuid(UUID.fromString(buyer.uuid()), pageable));
    }

    /**
     * SUPER_ADMIN oversight read: EVERY buyer's orders, newest-first per the
     * controller's pageable, optionally narrowed to one buyer. Role gating is
     * the controller's {@code @PreAuthorize}; nothing here is owner-scoped.
     */
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
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
        for (MarketOrderItem item : itemRepository.findByOrderId(order.getId())) {
            // Restock-alert foundation: read the pre-release stock so a release
            // that brings a sold-out listing back publishes ListingRestocked.
            // The extra SELECT rides the same tx; the AFTER_COMMIT listener
            // only fires if this cancel/expiry actually commits.
            Integer before = listingRepository.stockQtyOf(item.getListingId());
            listingRepository.restock(item.getListingId(), item.getQuantity());
            if (before != null && before == 0 && item.getQuantity() > 0) {
                eventPublisher.publishEvent(new ListingRestocked(item.getListingId()));
            }
        }
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

    private void storeReplayBody(String keyHash, OrderResponse response) {
        try {
            idempotencyService.complete(keyHash, HttpStatus.CREATED.value(),
                    objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException | RuntimeException ex) {
            // The order is committed and will be returned regardless; a lost
            // replay body means a stale-claim takeover re-executes and hits the
            // market_order.idempotency_key unique backstop instead of replaying.
            log.error("Failed to store idempotent replay body for order {}", response.orderRef(), ex);
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
