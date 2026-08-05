package com.innbucks.marketplaceservice.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.idempotency.ClaimResult;
import com.innbucks.marketplaceservice.idempotency.IdempotencyService;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.dto.ConfirmPaymentRequest;
import com.innbucks.marketplaceservice.order.dto.CreateOrderRequest;
import com.innbucks.marketplaceservice.order.dto.InternalOrderView;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
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
import java.util.stream.Collectors;

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

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

    private final MarketOrderRepository orderRepository;
    private final MarketOrderItemRepository itemRepository;
    private final ListingRepository listingRepository;
    private final OrderTransitionService transitions;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final MarketplaceMetrics metrics;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    private final int maxItems;
    private final int maxQuantityPerItem;
    private final long paymentTtlMinutes;
    private final String country;
    private final String currency;

    public OrderService(MarketOrderRepository orderRepository,
                        MarketOrderItemRepository itemRepository,
                        ListingRepository listingRepository,
                        OrderTransitionService transitions,
                        IdempotencyService idempotencyService,
                        AuditService auditService,
                        MarketplaceMetrics metrics,
                        ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager,
                        @Value("${marketplace.order.max-items}") int maxItems,
                        @Value("${marketplace.order.max-quantity-per-item}") int maxQuantityPerItem,
                        @Value("${marketplace.order.payment-ttl-minutes}") long paymentTtlMinutes,
                        @Value("${innbucks.country}") String country,
                        @Value("${innbucks.currency}") String currency) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.listingRepository = listingRepository;
        this.transitions = transitions;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxItems = maxItems;
        this.maxQuantityPerItem = maxQuantityPerItem;
        this.paymentTtlMinutes = paymentTtlMinutes;
        this.country = country;
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
        List<CreateOrderRequest.Item> items = request.items();
        if (items == null || items.isEmpty() || items.size() > maxItems) {
            throw ApiException.badRequest("invalid_items",
                    "Order must contain between 1 and " + maxItems + " line items");
        }
        Set<UUID> seen = new HashSet<>();
        for (CreateOrderRequest.Item item : items) {
            if (item.listingId() == null || item.quantity() == null) {
                // Bean Validation already rejects these; defensive for direct callers.
                throw ApiException.badRequest("invalid_items", "Each line needs listingId and quantity");
            }
            if (item.quantity() < 1 || item.quantity() > maxQuantityPerItem) {
                throw ApiException.badRequest("invalid_quantity",
                        "Quantity for listing " + item.listingId() + " must be between 1 and "
                                + maxQuantityPerItem);
            }
            if (!seen.add(item.listingId())) {
                throw ApiException.badRequest("duplicate_listing",
                        "Listing " + item.listingId() + " appears more than once in the order");
            }
        }
        String buyerMsisdn = normalizeMsisdn(request.buyerMsisdn());

        Map<UUID, Listing> listings = listingRepository.findAllById(seen).stream()
                .collect(Collectors.toMap(Listing::getId, l -> l));
        for (CreateOrderRequest.Item item : items) {
            Listing listing = listings.get(item.listingId());
            // One code for missing / not ACTIVE / foreign currency: the client
            // remedy is identical (drop the line), and it leaks nothing more
            // than the public catalog already shows.
            if (listing == null
                    || listing.getStatus() != ListingStatus.ACTIVE
                    || !currency.equals(listing.getCurrency())) {
                throw ApiException.unprocessable("listing_unavailable",
                        "Listing " + item.listingId() + " is not available");
            }
        }

        // Totals SERVER-SIDE from the listing rows, overflow-checked. Computed
        // before any stock is touched so a pathological order aborts cheaply.
        long totalCents = 0;
        List<OrderResponse.Line> lines = new ArrayList<>(items.size());
        try {
            for (CreateOrderRequest.Item item : items) {
                Listing listing = listings.get(item.listingId());
                long lineTotal = Math.multiplyExact(listing.getPriceCents(),
                        (long) item.quantity());
                totalCents = Math.addExact(totalCents, lineTotal);
                lines.add(new OrderResponse.Line(listing.getId(), listing.getTitle(),
                        listing.getPriceCents(), item.quantity(), lineTotal));
            }
        } catch (ArithmeticException ex) {
            throw ApiException.unprocessable("order_total_overflow",
                    "Order total exceeds the maximum representable amount");
        }

        reserveStock(items);

        Instant now = Instant.now();
        MarketOrder order = MarketOrder.builder()
                .id(UUID.randomUUID())
                .orderRef(newOrderRef())
                .buyerUuid(UUID.fromString(buyer.uuid()))
                .buyerMsisdn(buyerMsisdn)
                .status(OrderStatus.PENDING_PAYMENT)
                .totalCents(totalCents)
                .currency(currency)
                .expiresAt(now.plus(paymentTtlMinutes, ChronoUnit.MINUTES))
                .stockReleased(false)
                .idempotencyKey(keyHash)
                .createdAt(now)
                .updatedAt(now)
                .build();
        orderRepository.save(order);
        itemRepository.saveAll(lines.stream()
                .map(line -> MarketOrderItem.builder()
                        .id(UUID.randomUUID())
                        .orderId(order.getId())
                        .listingId(line.listingId())
                        .titleSnapshot(line.titleSnapshot())
                        .unitPriceCents(line.unitPriceCents())
                        .quantity(line.quantity())
                        .lineTotalCents(line.lineTotalCents())
                        .build())
                .toList());
        transitions.journalCreation(order);
        log.info("order created id={} ref={} lines={} totalCents={}",
                order.getId(), order.getOrderRef(), lines.size(), totalCents);
        return toResponse(order, lines);
    }

    /**
     * Reserves every line atomically, SORTED BY LISTING ID so two concurrent
     * orders over the same listings always lock in the same sequence (deadlock
     * avoidance). A 0 update-count (insufficient stock or listing no longer
     * ACTIVE) restocks everything already reserved and aborts with 409. The
     * explicit restock keeps the guarantee even if this loop ever moves
     * outside the creating transaction; today the rollback would also undo it.
     */
    private void reserveStock(List<CreateOrderRequest.Item> items) {
        List<CreateOrderRequest.Item> sorted = items.stream()
                .sorted(Comparator.comparing(CreateOrderRequest.Item::listingId))
                .toList();
        List<CreateOrderRequest.Item> reserved = new ArrayList<>(sorted.size());
        for (CreateOrderRequest.Item item : sorted) {
            if (listingRepository.reserveStock(item.listingId(), item.quantity()) == 0) {
                for (CreateOrderRequest.Item taken : reserved) {
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
        return toResponse(order, itemRepository.findByOrderId(order.getId()).stream()
                .map(OrderService::toLine).toList());
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
        return toResponse(order, itemRepository.findByOrderId(order.getId()).stream()
                .map(OrderService::toLine).toList());
    }

    /** Bulk-loads every page row's items in ONE query (no N+1). */
    private Page<OrderResponse> withItems(Page<MarketOrder> page) {
        List<UUID> orderIds = page.getContent().stream().map(MarketOrder::getId).toList();
        Map<UUID, List<MarketOrderItem>> itemsByOrder = orderIds.isEmpty() ? Map.of()
                : itemRepository.findByOrderIdIn(orderIds).stream()
                        .collect(Collectors.groupingBy(MarketOrderItem::getOrderId));
        return page.map(order -> toResponse(order,
                itemsByOrder.getOrDefault(order.getId(), List.of()).stream()
                        .map(OrderService::toLine).toList()));
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
            listingRepository.restock(item.getListingId(), item.getQuantity());
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

    private String normalizeMsisdn(String raw) {
        try {
            Phonenumber.PhoneNumber parsed = PHONE_UTIL.parse(raw, country);
            if (!PHONE_UTIL.isValidNumber(parsed)) {
                throw ApiException.badRequest("invalid_msisdn",
                        "buyerMsisdn is not a valid phone number");
            }
            return PHONE_UTIL.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException ex) {
            // Never log the raw value — it's a (possibly mistyped) phone number.
            throw ApiException.badRequest("invalid_msisdn",
                    "buyerMsisdn is not a valid phone number");
        }
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

    private static OrderResponse toResponse(MarketOrder order, List<OrderResponse.Line> lines) {
        return new OrderResponse(order.getId(), order.getOrderRef(), order.getStatus(),
                order.getTotalCents(), order.getCurrency(), order.getExpiresAt(),
                order.getCreatedAt(), lines);
    }

    private static OrderResponse.Line toLine(MarketOrderItem item) {
        return new OrderResponse.Line(item.getListingId(), item.getTitleSnapshot(),
                item.getUnitPriceCents(), item.getQuantity(), item.getLineTotalCents());
    }

    private static InternalOrderView toView(MarketOrder order) {
        return new InternalOrderView(order.getOrderRef(), order.getStatus(), order.getTotalCents(),
                order.getCurrency(), order.getBuyerMsisdn(), order.getExpiresAt());
    }
}
