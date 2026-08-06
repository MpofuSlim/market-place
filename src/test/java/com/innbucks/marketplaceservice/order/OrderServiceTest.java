package com.innbucks.marketplaceservice.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link OrderService} (no Spring context): the
 * server-side pricing invariants, item/quantity caps, the reserve/restock
 * stock discipline, MSISDN normalisation, the idempotency claim mapping, the
 * owner-scoped cancel with its exactly-once stock release, and the S2S
 * confirm-payment semantics with the 100x amount guard.
 *
 * <p>{@code PlatformTransactionManager} is a mock — {@code TransactionTemplate}
 * drives the callback straight through it, so the business logic runs exactly
 * as written while transactions are a no-op.
 */
class OrderServiceTest {

    private static final int MAX_ITEMS = 3;
    private static final int MAX_QTY_PER_ITEM = 10;
    private static final long TTL_MINUTES = 30;

    private static final UUID BUYER_UUID =
            UUID.fromString("6f9619ff-8b86-4011-b42d-00c04fc964ff");
    private static final AuthenticatedUser BUYER = new AuthenticatedUser(
            BUYER_UUID.toString(), Set.of("CUSTOMER"), null, null, null, "ZW");
    private static final String RAW_KEY = "order-key-1";
    private static final String KEY_HASH =
            IdempotencyService.namespaced(BUYER_UUID.toString(), RAW_KEY);
    private static final String ORDER_REF = "MKT-4F9A1C22B7D3";

    private MarketOrderRepository orderRepository;
    private MarketOrderItemRepository itemRepository;
    private ListingRepository listingRepository;
    private OrderTransitionService transitions;
    private IdempotencyService idempotencyService;
    private AuditService auditService;
    private SimpleMeterRegistry registry;
    private ObjectMapper objectMapper;
    private OrderService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(MarketOrderRepository.class);
        itemRepository = mock(MarketOrderItemRepository.class);
        listingRepository = mock(ListingRepository.class);
        transitions = mock(OrderTransitionService.class);
        idempotencyService = mock(IdempotencyService.class);
        auditService = mock(AuditService.class);
        registry = new SimpleMeterRegistry();
        objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        service = new OrderService(orderRepository, itemRepository, listingRepository,
                transitions, idempotencyService, auditService,
                new MarketplaceMetrics(registry), objectMapper,
                mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(PlatformTransactionManager.class),
                MAX_ITEMS, MAX_QTY_PER_ITEM, TTL_MINUTES, "ZW", "USD");
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(idempotencyService.claim(anyString(), anyString()))
                .thenReturn(new ClaimResult.New());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static CreateOrderRequest.Item item(UUID listingId, int quantity) {
        return new CreateOrderRequest.Item(listingId, quantity);
    }

    private static CreateOrderRequest req(String msisdn, CreateOrderRequest.Item... items) {
        return new CreateOrderRequest(msisdn, List.of(items));
    }

    private static Listing listing(UUID id, long priceCents, String title) {
        Instant now = Instant.now();
        return Listing.builder()
                .id(id).merchantId(UUID.randomUUID()).title(title)
                .priceCents(priceCents).currency("USD").stockQty(100)
                .status(ListingStatus.ACTIVE).createdAt(now).updatedAt(now)
                .build();
    }

    private static MarketOrder order(OrderStatus status) {
        Instant now = Instant.now();
        return MarketOrder.builder()
                .id(UUID.randomUUID()).orderRef(ORDER_REF)
                .buyerUuid(BUYER_UUID).buyerMsisdn("+263771234567")
                .status(status).totalCents(3550).currency("USD")
                .expiresAt(now.plusSeconds(1800)).stockReleased(false)
                .createdAt(now).updatedAt(now)
                .build();
    }

    private static MarketOrderItem orderItem(UUID orderId, UUID listingId, int quantity) {
        return MarketOrderItem.builder()
                .id(UUID.randomUUID()).orderId(orderId).listingId(listingId)
                .titleSnapshot("Item").unitPriceCents(100).quantity(quantity)
                .lineTotalCents(100L * quantity)
                .build();
    }

    private ApiException createFails(CreateOrderRequest request) {
        return assertThrows(ApiException.class,
                () -> service.createOrder(BUYER, request, RAW_KEY));
    }

    // ------------------------------------------------------------------
    // Creation: server-side pricing
    // ------------------------------------------------------------------

    @Test
    void createOrderComputesTotalServerSideFromListingPrices() throws Exception {
        UUID id1 = new UUID(0, 1);
        UUID id2 = new UUID(0, 2);
        when(listingRepository.findAllById(any())).thenReturn(List.of(
                listing(id1, 1550, "Solar Lantern 20W"), listing(id2, 450, "USB Cable")));
        when(listingRepository.reserveStock(any(UUID.class), anyInt())).thenReturn(1);

        OrderResponse resp = service.createOrder(BUYER,
                req("0771234567", item(id1, 2), item(id2, 1)), RAW_KEY);

        // Total = sum of listing-price * qty — the client supplied no prices.
        assertEquals(3550L, resp.totalCents());
        assertEquals(OrderStatus.PENDING_PAYMENT, resp.status());
        assertEquals("USD", resp.currency());
        assertTrue(resp.orderRef().matches("MKT-[0-9A-F]{12}"), resp.orderRef());
        assertEquals(2, resp.items().size());
        OrderResponse.Line line1 = resp.items().get(0);
        assertEquals(id1, line1.listingId());
        assertEquals("Solar Lantern 20W", line1.titleSnapshot());
        assertEquals(1550L, line1.unitPriceCents());
        assertEquals(2, line1.quantity());
        assertEquals(3100L, line1.lineTotalCents());
        assertEquals(450L, resp.items().get(1).lineTotalCents());

        ArgumentCaptor<MarketOrder> savedOrder = ArgumentCaptor.forClass(MarketOrder.class);
        verify(orderRepository).save(savedOrder.capture());
        MarketOrder order = savedOrder.getValue();
        assertEquals(BUYER_UUID, order.getBuyerUuid());
        assertEquals("+263771234567", order.getBuyerMsisdn()); // normalised to E.164
        assertEquals(3550L, order.getTotalCents());
        assertEquals(KEY_HASH, order.getIdempotencyKey());
        assertFalse(order.isStockReleased());
        assertEquals(order.getCreatedAt().plus(Duration.ofMinutes(TTL_MINUTES)),
                order.getExpiresAt());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarketOrderItem>> savedItems = ArgumentCaptor.forClass(List.class);
        verify(itemRepository).saveAll(savedItems.capture());
        assertEquals(2, savedItems.getValue().size());
        MarketOrderItem persisted1 = savedItems.getValue().get(0);
        assertEquals(order.getId(), persisted1.getOrderId());
        assertEquals(1550L, persisted1.getUnitPriceCents());
        assertEquals(3100L, persisted1.getLineTotalCents());

        verify(listingRepository).reserveStock(id1, 2);
        verify(listingRepository).reserveStock(id2, 1);
        verify(transitions).journalCreation(order);

        // Replay body stored with the ORIGINAL 201 status and round-trips.
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(idempotencyService).complete(eq(KEY_HASH), eq(201), body.capture());
        assertEquals(resp, objectMapper.readValue(body.getValue(), OrderResponse.class));

        verify(auditService).record(eq(AuditEventType.ORDER_CREATED),
                eq(BUYER_UUID.toString()), eq(order.getId().toString()), anyMap());
        assertEquals(1.0, registry.get("marketplace.orders")
                .tag("outcome", "created").counter().count());
    }

    @Test
    void lineTotalOverflowRejects422BeforeTouchingStockAndReleasesTheClaim() {
        UUID id = new UUID(0, 9);
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(listing(id, Long.MAX_VALUE, "Overflow")));

        ApiException ex = createFails(req("0771234567", item(id, 2)));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.status());
        assertEquals("order_total_overflow", ex.code());
        verify(listingRepository, never()).reserveStock(any(), anyInt());
        verify(orderRepository, never()).save(any());
        verify(idempotencyService).release(KEY_HASH);
    }

    @Test
    void grandTotalOverflowAcrossLinesRejects422() {
        UUID id1 = new UUID(0, 1);
        UUID id2 = new UUID(0, 2);
        when(listingRepository.findAllById(any())).thenReturn(List.of(
                listing(id1, Long.MAX_VALUE, "Max"), listing(id2, 1, "One more cent")));

        ApiException ex = createFails(req("0771234567", item(id1, 1), item(id2, 1)));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.status());
        assertEquals("order_total_overflow", ex.code());
        verify(listingRepository, never()).reserveStock(any(), anyInt());
    }

    // ------------------------------------------------------------------
    // Creation: item validation
    // ------------------------------------------------------------------

    @Test
    void duplicateListingIdsAreRejected() {
        UUID id = new UUID(0, 5);

        ApiException ex = createFails(req("0771234567", item(id, 1), item(id, 2)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("duplicate_listing", ex.code());
        verify(listingRepository, never()).findAllById(any());
        verify(idempotencyService).release(KEY_HASH);
    }

    @Test
    void moreLinesThanMaxItemsIsRejected() {
        CreateOrderRequest.Item[] tooMany = new CreateOrderRequest.Item[MAX_ITEMS + 1];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = item(new UUID(0, i + 1), 1);
        }

        ApiException ex = createFails(req("0771234567", tooMany));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("invalid_items", ex.code());
    }

    @Test
    void emptyItemListIsRejected() {
        ApiException ex = createFails(new CreateOrderRequest("0771234567", List.of()));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("invalid_items", ex.code());
    }

    @Test
    void quantityAbovePerItemCapIsRejected() {
        ApiException ex = createFails(
                req("0771234567", item(new UUID(0, 1), MAX_QTY_PER_ITEM + 1)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("invalid_quantity", ex.code());
    }

    @Test
    void quantityBelowOneIsRejected() {
        ApiException ex = createFails(req("0771234567", item(new UUID(0, 1), 0)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("invalid_quantity", ex.code());
    }

    @Test
    void unavailableListingRejects422() {
        UUID id = new UUID(0, 7);
        Listing inactive = listing(id, 1000, "Gone");
        inactive.setStatus(ListingStatus.INACTIVE);
        when(listingRepository.findAllById(any())).thenReturn(List.of(inactive));

        ApiException ex = createFails(req("0771234567", item(id, 1)));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.status());
        assertEquals("listing_unavailable", ex.code());
        verify(listingRepository, never()).reserveStock(any(), anyInt());
    }

    // ------------------------------------------------------------------
    // Creation: stock reservation
    // ------------------------------------------------------------------

    @Test
    void insufficientStockRestocksExactlyWhatWasAlreadyReserved() {
        UUID id1 = new UUID(0, 1);
        UUID id2 = new UUID(0, 2);
        UUID id3 = new UUID(0, 3);
        when(listingRepository.findAllById(any())).thenReturn(List.of(
                listing(id1, 100, "A"), listing(id2, 200, "B"), listing(id3, 300, "C")));
        when(listingRepository.reserveStock(id1, 2)).thenReturn(1);
        when(listingRepository.reserveStock(id2, 3)).thenReturn(1);
        when(listingRepository.reserveStock(id3, 4)).thenReturn(0);

        // Request order scrambled on purpose — reservation must run in
        // listing-id order (deadlock avoidance).
        ApiException ex = createFails(
                req("0771234567", item(id3, 4), item(id1, 2), item(id2, 3)));

        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("insufficient_stock", ex.code());

        InOrder ordered = inOrder(listingRepository);
        ordered.verify(listingRepository).reserveStock(id1, 2);
        ordered.verify(listingRepository).reserveStock(id2, 3);
        ordered.verify(listingRepository).reserveStock(id3, 4);

        // Only the two lines actually reserved are restocked — never the failed one.
        verify(listingRepository).restock(id1, 2);
        verify(listingRepository).restock(id2, 3);
        verify(listingRepository, never()).restock(eq(id3), anyInt());
        verify(orderRepository, never()).save(any());
        verify(idempotencyService).release(KEY_HASH);
    }

    // ------------------------------------------------------------------
    // Creation: MSISDN normalisation
    // ------------------------------------------------------------------

    @Test
    void unparseableMsisdnRejectsInvalidMsisdn() {
        ApiException ex = createFails(req("not-a-phone", item(new UUID(0, 1), 1)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("invalid_msisdn", ex.code());
        verify(listingRepository, never()).reserveStock(any(), anyInt());
        verify(idempotencyService).release(KEY_HASH);
    }

    @Test
    void parseableButInvalidMsisdnRejectsInvalidMsisdn() {
        // "123" parses under region ZW but fails isValidNumber.
        ApiException ex = createFails(req("123", item(new UUID(0, 1), 1)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("invalid_msisdn", ex.code());
    }

    // ------------------------------------------------------------------
    // Creation: idempotency claim mapping
    // ------------------------------------------------------------------

    @Test
    void missingIdempotencyKeyIsRejectedBeforeClaiming() {
        CreateOrderRequest request = req("0771234567", item(new UUID(0, 1), 1));

        ApiException noneEx = assertThrows(ApiException.class,
                () -> service.createOrder(BUYER, request, null));
        ApiException blankEx = assertThrows(ApiException.class,
                () -> service.createOrder(BUYER, request, "   "));

        assertEquals(HttpStatus.BAD_REQUEST, noneEx.status());
        assertEquals("idempotency_key_required", noneEx.code());
        assertEquals("idempotency_key_required", blankEx.code());
        verifyNoInteractions(idempotencyService);
    }

    @Test
    void keyIsTrimmedAndNamespacedPerBuyerBeforeClaiming() {
        UUID id = new UUID(0, 1);
        when(listingRepository.findAllById(any())).thenReturn(List.of(listing(id, 100, "A")));
        when(listingRepository.reserveStock(any(UUID.class), anyInt())).thenReturn(1);

        service.createOrder(BUYER, req("0771234567", item(id, 1)), "  " + RAW_KEY + "  ");

        verify(idempotencyService).claim(eq(KEY_HASH), anyString());
    }

    @Test
    void replayReturnsTheStoredResponseWithoutExecutingAnything() throws Exception {
        OrderResponse stored = new OrderResponse(UUID.randomUUID(), "MKT-AAAABBBBCCCC",
                OrderStatus.PENDING_PAYMENT, 3550, "USD",
                Instant.now().plusSeconds(1800), Instant.now(),
                List.of(new OrderResponse.Line(new UUID(0, 1), "Solar Lantern 20W",
                        1550, 2, 3100)));
        when(idempotencyService.claim(anyString(), anyString())).thenReturn(
                new ClaimResult.Replay(201, objectMapper.writeValueAsString(stored)));

        OrderResponse resp = service.createOrder(BUYER,
                req("0771234567", item(new UUID(0, 1), 2)), RAW_KEY);

        assertEquals(stored, resp);
        verifyNoInteractions(listingRepository, orderRepository, itemRepository,
                transitions, auditService);
        verify(idempotencyService, never()).complete(anyString(), anyInt(), anyString());
    }

    @Test
    void inFlightClaimMapsTo409() {
        when(idempotencyService.claim(anyString(), anyString()))
                .thenReturn(new ClaimResult.InFlight());

        ApiException ex = createFails(req("0771234567", item(new UUID(0, 1), 1)));

        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("request_in_flight", ex.code());
        verifyNoInteractions(listingRepository, orderRepository);
    }

    @Test
    void keyReuseWithDifferentBodyMapsTo422() {
        when(idempotencyService.claim(anyString(), anyString()))
                .thenReturn(new ClaimResult.Mismatch());

        ApiException ex = createFails(req("0771234567", item(new UUID(0, 1), 1)));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.status());
        assertEquals("idempotency_key_reuse", ex.code());
        verifyNoInteractions(listingRepository, orderRepository);
    }

    // ------------------------------------------------------------------
    // Cancel: owner-only, restocks exactly once
    // ------------------------------------------------------------------

    @Test
    void cancelIsOwnerScopedNotYoursIsTheSame404AsNotFound() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndBuyerUuid(orderId, BUYER_UUID))
                .thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.cancelOrder(BUYER, orderId));

        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("order_not_found", ex.code());
        verifyNoInteractions(transitions, listingRepository);
    }

    @Test
    void cancelTransitionsAndRestocksEveryLineExactlyOnce() {
        MarketOrder order = order(OrderStatus.PENDING_PAYMENT);
        UUID id1 = new UUID(0, 1);
        UUID id2 = new UUID(0, 2);
        when(orderRepository.findByIdAndBuyerUuid(order.getId(), BUYER_UUID))
                .thenReturn(Optional.of(order));
        when(itemRepository.findByOrderId(order.getId())).thenReturn(List.of(
                orderItem(order.getId(), id1, 2), orderItem(order.getId(), id2, 3)));
        when(transitions.transition(any(), eq(OrderStatus.CANCELLED), anyString()))
                .thenAnswer(inv -> {
                    MarketOrder o = inv.getArgument(0);
                    o.setStatus(OrderStatus.CANCELLED);
                    return o;
                });

        OrderResponse resp = service.cancelOrder(BUYER, order.getId());

        assertEquals(OrderStatus.CANCELLED, resp.status());
        verify(transitions).transition(order, OrderStatus.CANCELLED, "Cancelled by buyer");
        verify(listingRepository, times(1)).restock(id1, 2);
        verify(listingRepository, times(1)).restock(id2, 3);
        assertTrue(order.isStockReleased());
        verify(orderRepository).save(order);
    }

    @Test
    void cancelNeverRestocksWhenStockWasAlreadyReleased() {
        // stock_released already set (e.g. a racing expiry released it) — the
        // guard makes the release a no-op, never a second restock.
        MarketOrder order = order(OrderStatus.PENDING_PAYMENT);
        order.setStockReleased(true);
        when(orderRepository.findByIdAndBuyerUuid(order.getId(), BUYER_UUID))
                .thenReturn(Optional.of(order));
        when(itemRepository.findByOrderId(order.getId())).thenReturn(List.of());

        service.cancelOrder(BUYER, order.getId());

        verify(listingRepository, never()).restock(any(), anyInt());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void refusedCancelReleasesNoStock() {
        MarketOrder order = order(OrderStatus.PAID);
        when(orderRepository.findByIdAndBuyerUuid(order.getId(), BUYER_UUID))
                .thenReturn(Optional.of(order));
        when(transitions.transition(any(), eq(OrderStatus.CANCELLED), anyString()))
                .thenThrow(ApiException.conflict("illegal_order_state", "refused"));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.cancelOrder(BUYER, order.getId()));

        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("illegal_order_state", ex.code());
        // The refusal happens BEFORE the release — stock stays reserved.
        verify(listingRepository, never()).restock(any(), anyInt());
        assertFalse(order.isStockReleased());
    }

    // ------------------------------------------------------------------
    // Confirm-payment semantics (S2S)
    // ------------------------------------------------------------------

    @Nested
    class ConfirmPayment {

        private MarketOrder pending;

        @BeforeEach
        void setUp() {
            pending = order(OrderStatus.PENDING_PAYMENT);
            when(orderRepository.findByOrderRef(ORDER_REF)).thenReturn(Optional.of(pending));
        }

        @Test
        void exactAmountConfirmsToPaid() {
            when(transitions.transition(any(), eq(OrderStatus.PAID), anyString()))
                    .thenAnswer(inv -> {
                        MarketOrder o = inv.getArgument(0);
                        o.setStatus(OrderStatus.PAID);
                        return o;
                    });

            InternalOrderView view = service.confirmPayment(ORDER_REF,
                    new ConfirmPaymentRequest("PAY-REF-1", 3550L));

            assertEquals(OrderStatus.PAID, view.status());
            assertEquals(ORDER_REF, view.orderRef());
            assertEquals(3550L, view.totalCents());
            assertEquals("PAY-REF-1", pending.getPaymentRef());
            assertNotNull(pending.getPaidAt());
            verify(transitions).transition(eq(pending), eq(OrderStatus.PAID), anyString());
        }

        @Test
        void amountMismatchRejects422AuditsCountsAndChangesNothing() {
            ApiException ex = assertThrows(ApiException.class,
                    () -> service.confirmPayment(ORDER_REF,
                            new ConfirmPaymentRequest("PAY-REF-1", 9999L)));

            assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.status());
            assertEquals("amount_mismatch", ex.code());

            // Metric: the 100x guard tripped — page-worthy.
            assertEquals(1.0, registry.get("marketplace.orders.confirm_mismatch")
                    .counter().count());

            // Audit: expected vs received recorded against the order.
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
            verify(auditService).record(eq(AuditEventType.ORDER_CONFIRM_AMOUNT_MISMATCH),
                    eq("system"), eq(pending.getId().toString()), meta.capture());
            assertEquals(3550L, meta.getValue().get("expectedCents"));
            assertEquals(9999L, meta.getValue().get("receivedCents"));
            assertEquals("PAY-REF-1", meta.getValue().get("paymentRef"));

            // NO state change: still pending, unpaid, unsaved.
            assertEquals(OrderStatus.PENDING_PAYMENT, pending.getStatus());
            assertNull(pending.getPaymentRef());
            assertNull(pending.getPaidAt());
            verifyNoInteractions(transitions);
            verify(orderRepository, never()).save(any());
        }

        @Test
        void replayWithTheSamePaymentRefIsAnOkNoOp() {
            pending.setStatus(OrderStatus.PAID);
            pending.setPaymentRef("PAY-REF-1");
            Instant paidAt = Instant.now();
            pending.setPaidAt(paidAt);

            InternalOrderView view = service.confirmPayment(ORDER_REF,
                    new ConfirmPaymentRequest("PAY-REF-1", 3550L));

            assertEquals(OrderStatus.PAID, view.status());
            assertEquals(paidAt, pending.getPaidAt()); // untouched
            verifyNoInteractions(transitions, auditService);
            verify(orderRepository, never()).save(any());
        }

        @Test
        void paidWithADifferentPaymentRefConflicts() {
            pending.setStatus(OrderStatus.PAID);
            pending.setPaymentRef("PAY-REF-1");

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.confirmPayment(ORDER_REF,
                            new ConfirmPaymentRequest("PAY-REF-2", 3550L)));

            assertEquals(HttpStatus.CONFLICT, ex.status());
            assertEquals("order_already_paid", ex.code());
            assertEquals("PAY-REF-1", pending.getPaymentRef()); // original wins
            verifyNoInteractions(transitions);
        }

        @Test
        void expiredOrderIsNotConfirmable() {
            pending.setStatus(OrderStatus.EXPIRED);

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.confirmPayment(ORDER_REF,
                            new ConfirmPaymentRequest("PAY-REF-1", 3550L)));

            assertEquals(HttpStatus.CONFLICT, ex.status());
            assertEquals("order_not_confirmable", ex.code());
            verifyNoInteractions(transitions);
        }

        @Test
        void cancelledOrderIsNotConfirmable() {
            pending.setStatus(OrderStatus.CANCELLED);

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.confirmPayment(ORDER_REF,
                            new ConfirmPaymentRequest("PAY-REF-1", 3550L)));

            assertEquals(HttpStatus.CONFLICT, ex.status());
            assertEquals("order_not_confirmable", ex.code());
        }

        @Test
        void unknownOrderRefIs404() {
            when(orderRepository.findByOrderRef("MKT-000000000000"))
                    .thenReturn(Optional.empty());

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.confirmPayment("MKT-000000000000",
                            new ConfirmPaymentRequest("PAY-REF-1", 3550L)));

            assertEquals(HttpStatus.NOT_FOUND, ex.status());
            assertEquals("order_not_found", ex.code());
        }
    }

    // ------------------------------------------------------------------
    // Reads: CUSTOMER owner-masking vs SUPER_ADMIN oversight
    // ------------------------------------------------------------------

    private static final AuthenticatedUser SUPER_ADMIN = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("SUPER_ADMIN"), null, null, null, "ZW");

    @Test
    void customerGetOrderStaysOwnerMaskedNotYoursIsTheSame404() {
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findByIdAndBuyerUuid(orderId, BUYER_UUID))
                .thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.getOrder(BUYER, orderId));

        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("order_not_found", ex.code());
        // Never the unscoped lookup for a customer.
        verify(orderRepository, never()).findById(any(UUID.class));
    }

    @Test
    void superAdminGetsAnyOrderByIdWithoutOwnerScoping() {
        MarketOrder order = order(OrderStatus.PENDING_PAYMENT);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(itemRepository.findByOrderId(order.getId()))
                .thenReturn(List.of(orderItem(order.getId(), new UUID(0, 1), 2)));

        OrderResponse resp = service.getOrder(SUPER_ADMIN, order.getId());

        assertEquals(order.getId(), resp.id());
        assertEquals(1, resp.items().size());
        // Fleet oversight is the plain findById — the buyer-scoped query would
        // 404 (the admin's uuid owns nothing).
        verify(orderRepository, never()).findByIdAndBuyerUuid(any(), any());
    }

    @Test
    void getAllWithoutAFilterPagesEveryBuyersOrders() {
        MarketOrder order = order(OrderStatus.PENDING_PAYMENT);
        when(orderRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(order)));
        when(itemRepository.findByOrderIdIn(List.of(order.getId())))
                .thenReturn(List.of(orderItem(order.getId(), new UUID(0, 1), 2)));

        var page = service.getAll(null, org.springframework.data.domain.PageRequest.of(0, 20));

        assertEquals(1, page.getTotalElements());
        assertEquals(order.getId(), page.getContent().getFirst().id());
        assertEquals(1, page.getContent().getFirst().items().size());
        verify(orderRepository, never()).findByBuyerUuid(any(), any());
    }

    @Test
    void getAllWithABuyerFilterNarrowsToThatBuyer() {
        when(orderRepository.findByBuyerUuid(eq(BUYER_UUID),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        service.getAll(BUYER_UUID, org.springframework.data.domain.PageRequest.of(0, 20));

        verify(orderRepository).findByBuyerUuid(eq(BUYER_UUID),
                any(org.springframework.data.domain.Pageable.class));
        verify(orderRepository, never())
                .findAll(any(org.springframework.data.domain.Pageable.class));
    }
}
