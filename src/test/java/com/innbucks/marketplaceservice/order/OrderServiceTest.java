package com.innbucks.marketplaceservice.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.cart.CartService;
import com.innbucks.marketplaceservice.checkout.BasketViewAssembler;
import com.innbucks.marketplaceservice.checkout.CheckoutPricer;
import com.innbucks.marketplaceservice.checkout.CheckoutProperties;
import com.innbucks.marketplaceservice.checkout.BasketLine;
import com.innbucks.marketplaceservice.checkout.CheckoutService;
import com.innbucks.marketplaceservice.delivery.DeliveryAddress;
import com.innbucks.marketplaceservice.delivery.DeliveryAddressService;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentService;
import com.innbucks.marketplaceservice.seller.SellerService;
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
import com.innbucks.marketplaceservice.order.dto.OrderLineRejection;
import com.innbucks.marketplaceservice.order.dto.OrderRejectionDetails;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.support.TestTowns;
import com.innbucks.marketplaceservice.catalog.ListingDeliveryTownRepository;
import com.innbucks.marketplaceservice.catalog.ListingDeliveryTown;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    private MarketOrderDeliveryFeeRepository deliveryFeeRepository;
    private ListingRepository listingRepository;
    private com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository variantRepository;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private ListingDeliveryTownRepository coverage;
    private OrderTransitionService transitions;
    private IdempotencyService idempotencyService;
    private AuditService auditService;
    private SimpleMeterRegistry registry;
    private ObjectMapper objectMapper;
    private CartService cartService;
    private FulfilmentService fulfilmentService;
    private com.innbucks.marketplaceservice.settlement.SettlementService settlementService;
    private DeliveryAddressService addressService;
    private CheckoutProperties checkoutProperties;
    private OrderService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(MarketOrderRepository.class);
        itemRepository = mock(MarketOrderItemRepository.class);
        deliveryFeeRepository = mock(MarketOrderDeliveryFeeRepository.class);
        listingRepository = mock(ListingRepository.class);
        coverage = mock(ListingDeliveryTownRepository.class);
        transitions = mock(OrderTransitionService.class);
        idempotencyService = mock(IdempotencyService.class);
        auditService = mock(AuditService.class);
        registry = new SimpleMeterRegistry();
        objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        // Real collaborators wherever they are pure, so these tests still
        // exercise the ACTUAL pricing, availability and delivery rules rather
        // than a stub's idea of them. Only the cart, the fulfilment queue and
        // the address book — which have their own tests — are mocked.
        cartService = mock(CartService.class);
        fulfilmentService = mock(FulfilmentService.class);
        addressService = mock(DeliveryAddressService.class);
        checkoutProperties = new CheckoutProperties();
        variantRepository = mock(com.innbucks.marketplaceservice.catalog.variant.ListingVariantRepository.class);
        CheckoutPricer pricer = new CheckoutPricer(listingRepository, coverage,
                TestTowns.zimbabwe(), "USD", variantRepository);
        CheckoutService checkoutService = new CheckoutService(checkoutProperties, pricer,
                mock(BasketViewAssembler.class), cartService, addressService,
                mock(com.innbucks.marketplaceservice.pickup.CollectionPointResolver.class),
                mock(com.innbucks.marketplaceservice.pickup.CollectionPointViews.class), "USD");
        settlementService = mock(com.innbucks.marketplaceservice.settlement.SettlementService.class);
        OrderViewAssembler views = new OrderViewAssembler(itemRepository, fulfilmentService,
                mock(com.innbucks.marketplaceservice.settlement.SettlementDisputeRepository.class),
                mock(SellerService.class), checkoutService,
                mock(com.innbucks.marketplaceservice.pickup.CollectionPointViews.class),
                mock(com.innbucks.marketplaceservice.settlement.MerchantSettlementRepository.class),
                new com.innbucks.marketplaceservice.fulfilment.BuyerParcelRules(7));
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        // The REAL stock mover over the mocked repositories, so these tests
        // still pin the exact statements an order issues.
        service = new OrderService(orderRepository, itemRepository, deliveryFeeRepository,
                new com.innbucks.marketplaceservice.catalog.ListingStock(listingRepository,
                        variantRepository, eventPublisher, new MarketplaceMetrics(registry)),
                transitions, idempotencyService, auditService,
                new MarketplaceMetrics(registry), objectMapper,
                mock(PlatformTransactionManager.class),
                checkoutService, pricer, cartService, fulfilmentService, settlementService, views,
                new Msisdns("ZW"),
                MAX_ITEMS, MAX_QTY_PER_ITEM, TTL_MINUTES, "USD");
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

    /** No deliveryMethod: the unstated default is COLLECTION, which is exactly
     *  what an order meant before delivery existed here — so these cases keep
     *  asserting the pre-V9 behaviour unchanged. */
    private static CreateOrderRequest req(String msisdn, CreateOrderRequest.Item... items) {
        return new CreateOrderRequest(msisdn, null, List.of(items), null, null, null);
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

    @Test
    void confirmReceivedRefusesAnotherOrdersParcelBeforeClosingIt() {
        // The parcel is the buyer's, but on a DIFFERENT order of theirs. It
        // must be refused BEFORE anything closes: closing first and then
        // rolling back left a FULFILMENT_DELIVERED audit row behind, because the
        // audit commits in its own transaction.
        MarketOrder order = order(OrderStatus.PAID);
        when(orderRepository.findByIdAndBuyerUuid(order.getId(), BUYER_UUID))
                .thenReturn(java.util.Optional.of(order));
        when(fulfilmentService.forOrder(order.getId())).thenReturn(List.of());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.confirmReceived(BUYER, order.getId(), UUID.randomUUID()));

        assertEquals("fulfilment_not_found", ex.code());
        verify(fulfilmentService, never()).confirmReceived(any(), any());
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
        ApiException ex = createFails(
                new CreateOrderRequest("0771234567", null, List.of(), null, null, null));

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
    // Creation: a refused order names EVERY failing line
    //
    // The order is still refused as a whole; what changed is that the customer
    // sees one complete correction instead of discovering the second problem
    // after fixing the first. The top-level status/code/message must stay
    // byte-identical to the old abort-at-first-failure behaviour, so these
    // tests pin BOTH halves.
    // ------------------------------------------------------------------

    private static Listing listingWithStock(UUID id, long priceCents, String title, int stockQty) {
        Listing l = listing(id, priceCents, title);
        l.setStockQty(stockQty);
        return l;
    }

    private static List<OrderLineRejection> rejectionsOf(ApiException ex) {
        assertNotNull(ex.details(), "a line-level refusal must carry details");
        assertInstanceOf(OrderRejectionDetails.class, ex.details());
        return ((OrderRejectionDetails) ex.details()).rejections();
    }

    @Test
    void everyUnavailableLineIsReported_notJustTheFirst() {
        UUID id1 = new UUID(0, 1);
        UUID id2 = new UUID(0, 2);
        Listing gone = listing(id1, 1000, "Gone");
        gone.setStatus(ListingStatus.INACTIVE);
        Listing archived = listing(id2, 2000, "Archived");
        archived.setStatus(ListingStatus.ARCHIVED);
        when(listingRepository.findAllById(any())).thenReturn(List.of(gone, archived));

        ApiException ex = createFails(req("0771234567", item(id1, 2), item(id2, 1)));

        // Top level: exactly what the first-failure version said.
        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.status());
        assertEquals("listing_unavailable", ex.code());
        assertEquals("Listing " + id1 + " is not available", ex.getMessage());

        List<OrderLineRejection> rejections = rejectionsOf(ex);
        assertEquals(2, rejections.size(), "both bad lines, not just the first");
        assertEquals(id1, rejections.get(0).listingId());
        assertEquals(id2, rejections.get(1).listingId());
        rejections.forEach(r -> {
            assertEquals(OrderLineRejection.REASON_UNAVAILABLE, r.reason());
            // Nothing is known about a listing that is off sale — inventing a
            // price or a quantity here would be worse than omitting them.
            assertNull(r.availableQty());
            assertNull(r.unitPriceCents());
        });
        verify(listingRepository, never()).reserveStock(any(), anyInt());
        verify(idempotencyService).release(KEY_HASH);
    }

    @Test
    void shortStockedLineCarriesItsAvailableQtyAndCurrentPrice() {
        UUID id = new UUID(0, 1);
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(listingWithStock(id, 1550, "Solar Lantern 20W", 3)));

        ApiException ex = createFails(req("0771234567", item(id, 5)));

        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("insufficient_stock", ex.code());

        OrderLineRejection only = rejectionsOf(ex).get(0);
        assertEquals(OrderLineRejection.REASON_INSUFFICIENT_STOCK, only.reason());
        assertEquals(5, only.requestedQty());
        assertEquals(3, only.availableQty());
        // The current price rides along so the app can show the corrected line
        // without a second fetch.
        assertEquals(1550L, only.unitPriceCents());
        // Named, so the customer reads something they can act on.
        assertEquals("Only 3 left of Solar Lantern 20W", only.message());

        // The pre-check refused before any stock was touched.
        verify(listingRepository, never()).reserveStock(any(), anyInt());
    }

    @Test
    void soldOutLineSaysSoldOut() {
        UUID id = new UUID(0, 1);
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(listingWithStock(id, 450, "USB-C Charging Cable 2m", 0)));

        ApiException ex = createFails(req("0771234567", item(id, 1)));

        OrderLineRejection only = rejectionsOf(ex).get(0);
        assertEquals(0, only.availableQty());
        assertEquals("USB-C Charging Cable 2m is sold out", only.message());
    }

    @Test
    void amongStockFailuresTheTopLevelNamesTheSmallestListingId() {
        // Byte-compatibility detail: reserveStock iterates SORTED BY LISTING ID
        // (deadlock avoidance) and was the thrower, so the id it named was the
        // smallest failing one — NOT the first in request order. The rejections
        // list still reads in request order, which is what a client renders.
        UUID low = new UUID(0, 1);
        UUID high = new UUID(0, 9);
        when(listingRepository.findAllById(any())).thenReturn(List.of(
                listingWithStock(low, 100, "Low", 0), listingWithStock(high, 200, "High", 0)));

        ApiException ex = createFails(req("0771234567", item(high, 1), item(low, 1)));

        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("Insufficient stock for listing " + low, ex.getMessage());

        List<OrderLineRejection> rejections = rejectionsOf(ex);
        assertEquals(List.of(high, low),
                rejections.stream().map(OrderLineRejection::listingId).toList(),
                "rejections stay in REQUEST order");
    }

    @Test
    void unavailabilityBeatsShortStockAtTheTopLevel_butBothAreReported() {
        // The old code checked availability for every line before touching any
        // stock, so an unavailable line always threw first regardless of
        // position. That precedence is preserved.
        UUID shortId = new UUID(0, 1);
        UUID goneId = new UUID(0, 9);
        Listing gone = listing(goneId, 2000, "Gone");
        gone.setStatus(ListingStatus.INACTIVE);
        when(listingRepository.findAllById(any())).thenReturn(List.of(
                listingWithStock(shortId, 450, "USB-C Charging Cable 2m", 1), gone));

        ApiException ex = createFails(req("0771234567", item(shortId, 4), item(goneId, 2)));

        assertEquals(HttpStatus.UNPROCESSABLE_CONTENT, ex.status());
        assertEquals("listing_unavailable", ex.code());
        assertEquals("Listing " + goneId + " is not available", ex.getMessage());

        List<OrderLineRejection> rejections = rejectionsOf(ex);
        assertEquals(2, rejections.size());
        assertEquals(OrderLineRejection.REASON_INSUFFICIENT_STOCK, rejections.get(0).reason());
        assertEquals(1, rejections.get(0).availableQty());
        assertEquals(OrderLineRejection.REASON_UNAVAILABLE, rejections.get(1).reason());
    }

    @Test
    void aLineThatLosesTheReserveRaceStillGetsThePlain409WithNoDetails() {
        // The pre-check is ADVISORY — stock read there can be stale. When a
        // concurrent buyer takes the last unit between the read and the atomic
        // UPDATE, the authoritative guard still refuses, and that refusal is
        // the unchanged single-line 409: we genuinely do not know what else
        // moved, so inventing a per-line report would be fiction.
        UUID id = new UUID(0, 1);
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(listingWithStock(id, 100, "Contested", 10)));
        when(listingRepository.reserveStock(id, 1)).thenReturn(0);

        ApiException ex = createFails(req("0771234567", item(id, 1)));

        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("insufficient_stock", ex.code());
        assertNull(ex.details(), "a lost race reports no per-line detail");
        verify(listingRepository).reserveStock(id, 1);
    }

    @Test
    void anAcceptableOrderStillReachesReserveStock() {
        // The pre-check must not become a second gate: stock that is sufficient
        // at read time proceeds to the atomic reservation exactly as before.
        UUID id = new UUID(0, 1);
        when(listingRepository.findAllById(any()))
                .thenReturn(List.of(listingWithStock(id, 100, "Plenty", 10)));
        when(listingRepository.reserveStock(id, 10)).thenReturn(1);

        OrderResponse resp = service.createOrder(BUYER, req("0771234567", item(id, 10)), RAW_KEY);

        assertEquals(1000L, resp.totalCents());
        verify(listingRepository).reserveStock(id, 10);
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
    // Creation: the payer is the CALLER — JWT phone beats the body
    // ------------------------------------------------------------------

    /** A real CUSTOMER login: same uuid as BUYER (so the same idempotency
     *  namespace), but the token carries the phoneNumber claim. */
    private static final AuthenticatedUser BUYER_WITH_PHONE = new AuthenticatedUser(
            BUYER_UUID.toString(), Set.of("CUSTOMER"), null, null, "+263779999999", "ZW");

    private MarketOrder createdBy(AuthenticatedUser buyer, CreateOrderRequest request) {
        UUID id = new UUID(0, 1);
        when(listingRepository.findAllById(any())).thenReturn(List.of(listing(id, 1000, "Thing")));
        when(listingRepository.reserveStock(any(UUID.class), anyInt())).thenReturn(1);
        service.createOrder(buyer, request, RAW_KEY);
        ArgumentCaptor<MarketOrder> saved = ArgumentCaptor.forClass(MarketOrder.class);
        verify(orderRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    void jwtPhoneWinsOverTheBodyMsisdn() {
        // The body names a DIFFERENT number. On the EcoCash rail this is the
        // phone that gets the PIN prompt, so the caller's own number must win
        // — otherwise any buyer could push prompts to any number they typed.
        MarketOrder order = createdBy(BUYER_WITH_PHONE, req("0771234567", item(new UUID(0, 1), 1)));

        assertEquals("+263779999999", order.getBuyerMsisdn());
    }

    @Test
    void jwtPhoneIsUsedWhenTheBodyOmitsMsisdn() {
        MarketOrder order = createdBy(BUYER_WITH_PHONE, req(null, item(new UUID(0, 1), 1)));

        assertEquals("+263779999999", order.getBuyerMsisdn());
    }

    @Test
    void bodyMsisdnIsUsedOnlyWhenTheTokenHasNoPhone() {
        // A phone-less token (legacy / staff-shaped): the body is the only
        // source, normalised to E.164 as before.
        MarketOrder order = createdBy(BUYER, req("0771234567", item(new UUID(0, 1), 1)));

        assertEquals("+263771234567", order.getBuyerMsisdn());
    }

    @Test
    void noPhoneAnywhereRejectsInvalidMsisdnBeforeTouchingStock() {
        ApiException ex = createFails(req(null, item(new UUID(0, 1), 1)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("invalid_msisdn", ex.code());
        verify(listingRepository, never()).reserveStock(any(), anyInt());
        verify(idempotencyService).release(KEY_HASH);
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
                OrderStatus.PENDING_PAYMENT, 3550, 0, 3550, "USD",
                DeliveryMethod.COLLECTION, null,
                Instant.now().plusSeconds(1800), Instant.now(), null,
                List.of(new OrderResponse.Line(new UUID(0, 1), "Solar Lantern 20W",
                        1550, 2, 3100)),
                null, null, List.of(), null, null, null);
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

    // ==================================================================
    // Checkout: cart sourcing, delivery and the money split (V9)
    // ==================================================================

    @Nested
    class Checkout {

        private final UUID listingId = new UUID(0, 1);

        private Listing oneSellableListing() {
            Listing listing = listing(listingId, 1550, "Solar Lantern 20W");
            when(listingRepository.findAllById(any())).thenReturn(List.of(listing));
            when(listingRepository.reserveStock(any(UUID.class), anyInt())).thenReturn(1);
            // Delivered to Harare (the saved address's town) for USD 2.00.
            when(coverage.findByListingIdIn(any()))
                    .thenReturn(List.of(new ListingDeliveryTown(listingId, "harare", 200)));
            return listing;
        }

        private DeliveryAddress savedAddress() {
            Instant now = Instant.now();
            return DeliveryAddress.builder()
                    .id(UUID.randomUUID()).buyerUuid(BUYER_UUID).label("Home")
                    .recipientName("Tariro Moyo").recipientMsisdn("+263771234567")
                    .line1("14 Samora Machel Ave").line2("Flat 3B").city("Harare")
                    .townCode("harare").area("Avondale").landmark("Opposite the clinic")
                    .defaultAddress(true).createdAt(now).updatedAt(now).version(0L).build();
        }

        private MarketOrder createdRow() {
            ArgumentCaptor<MarketOrder> captor = ArgumentCaptor.forClass(MarketOrder.class);
            verify(orderRepository).save(captor.capture());
            return captor.getValue();
        }

        private CreateOrderRequest giftReq(CreateOrderRequest.Recipient recipient) {
            return new CreateOrderRequest("0771234567", null, List.of(item(listingId, 1)),
                    null, null, recipient);
        }

        @Test
        void aGiftOrderStoresTheRecipientAndNormalisesTheirNumber() {
            oneSellableListing();

            OrderResponse response = service.createOrder(BUYER,
                    giftReq(new CreateOrderRequest.Recipient("Gogo Chipo Moyo", "0772345678",
                            "Happy birthday Gogo")), RAW_KEY);

            MarketOrder row = createdRow();
            assertEquals("Gogo Chipo Moyo", row.getRecipientName());
            // Through the SAME Msisdns as the payer's: a number this service
            // will message must mean the same thing on every surface.
            assertEquals("+263772345678", row.getRecipientMsisdn());
            assertEquals("Happy birthday Gogo", row.getGiftMessage());
            assertEquals("Gogo Chipo Moyo", response.recipient().name());
            assertEquals("+263772345678", response.recipient().msisdn());
        }

        @Test
        void anOrderWithNoRecipientIsNotAGift_andNothingIsDefaultedFromTheBuyer() {
            oneSellableListing();

            OrderResponse response = service.createOrder(BUYER,
                    req("0771234567", item(listingId, 1)), RAW_KEY);

            MarketOrder row = createdRow();
            // Filling these with the buyer's own details would assert a gift
            // nobody sent — every downstream surface reads "has a recipient"
            // as "is a gift".
            assertNull(row.getRecipientName());
            assertNull(row.getRecipientMsisdn());
            assertNull(response.recipient());
        }

        @Test
        void aRecipientNumberThatCannotBeDialledIsRefusedByFieldName() {
            oneSellableListing();

            ApiException ex = assertThrows(ApiException.class, () -> service.createOrder(BUYER,
                    giftReq(new CreateOrderRequest.Recipient("Gogo Chipo Moyo", "not-a-number",
                            null)), RAW_KEY));

            assertEquals(HttpStatus.BAD_REQUEST, ex.status());
            assertEquals("invalid_msisdn", ex.code());
            assertTrue(ex.getMessage().contains("recipient.msisdn"),
                    "the refusal must name the field so the app highlights the right input");
        }

        @Test
        void aRecipientNamedWithoutANumberIsStillAGift() {
            oneSellableListing();

            OrderResponse response = service.createOrder(BUYER,
                    giftReq(new CreateOrderRequest.Recipient("Gogo Chipo Moyo", null, null)),
                    RAW_KEY);

            // Nobody is messaged, the buyer passes the collection code on
            // themselves — but the parcel still carries whose it is.
            assertEquals("Gogo Chipo Moyo", response.recipient().name());
            assertNull(response.recipient().msisdn());
        }

        @Test
        void aNameThatIsNothingButMarkupIsRefused() {
            oneSellableListing();

            ApiException ex = assertThrows(ApiException.class, () -> service.createOrder(BUYER,
                    giftReq(new CreateOrderRequest.Recipient("<script>x()</script>", null, null)),
                    RAW_KEY));

            // Bean Validation rejects a BLANK name; a name that survives it and
            // then sanitizes down to nothing has to be caught here.
            assertEquals("recipient_name_required", ex.code());
        }

        @Test
        void anUnstatedMethodIsCollection_soAnOldClientBehavesExactlyAsBefore() {
            oneSellableListing();

            OrderResponse response = service.createOrder(BUYER,
                    req("0771234567", item(listingId, 2)), RAW_KEY);

            assertEquals(DeliveryMethod.COLLECTION, response.deliveryMethod());
            assertEquals(3100, response.subtotalCents());
            assertEquals(0, response.deliveryFeeCents());
            assertEquals(3100, response.totalCents());
            assertNull(response.deliveryAddress());
            // No address is resolved at all for a collection order.
            verifyNoInteractions(addressService);
        }

        @Test
        void aDeliveryOrderAddsTheSellersTownFeeToTheTotal() {
            Listing listing = oneSellableListing();
            when(addressService.requireForCheckout(any(), any())).thenReturn(savedAddress());

            OrderResponse response = service.createOrder(BUYER,
                    new CreateOrderRequest("0771234567", null, List.of(item(listingId, 2)),
                            DeliveryMethod.DELIVERY, null, null), RAW_KEY);

            assertEquals(3100, response.subtotalCents());
            assertEquals(200, response.deliveryFeeCents());
            // total = subtotal + fee, which is what the payments service collects
            assertEquals(3300, response.totalCents());
            // The town is snapshotted and the seller's fee fixed at order time:
            // a reprice before payment must not change what the buyer owes.
            assertEquals("harare", createdRow().getDeliveryTownCode());
            ArgumentCaptor<List<MarketOrderDeliveryFee>> fees = ArgumentCaptor.forClass(List.class);
            verify(deliveryFeeRepository).saveAll(fees.capture());
            assertEquals(1, fees.getValue().size());
            assertEquals(listing.getMerchantId(), fees.getValue().getFirst().getMerchantId());
            assertEquals(200, fees.getValue().getFirst().getFeeCents());
        }

        @Test
        void aCollectionOrderRecordsNoDeliveryFee() {
            oneSellableListing();

            service.createOrder(BUYER, req("0771234567", item(listingId, 1)), RAW_KEY);

            verify(deliveryFeeRepository, never()).saveAll(any());
            verifyNoInteractions(coverage);
        }

        @Test
        void aDeliveryToATownTheSellerDoesNotCoverIsRefusedBeforeAnyStockIsTouched() {
            oneSellableListing();
            when(coverage.findByListingIdIn(any()))
                    .thenReturn(List.of(new ListingDeliveryTown(listingId, "bulawayo", 900)));
            when(addressService.requireForCheckout(any(), any())).thenReturn(savedAddress());

            ApiException ex = assertThrows(ApiException.class, () -> service.createOrder(BUYER,
                    new CreateOrderRequest("0771234567", null, List.of(item(listingId, 1)),
                            DeliveryMethod.DELIVERY, null, null), RAW_KEY));

            assertEquals("not_delivered_to_town", ex.code());
            assertEquals("Solar Lantern 20W is not delivered to Harare. Choose collection or "
                    + "another address.", ex.getMessage());
            verify(listingRepository, never()).reserveStock(any(UUID.class), anyInt());
        }

        @Test
        void stockStillOutranksTownCoverageInTheHeadline() {
            // Pre-V14 refusals stay byte-identical: a short line is still the
            // 409 it always was, even when another line is not delivered here.
            UUID shortId = new UUID(0, 2);
            Listing covered = listing(listingId, 1550, "Solar Lantern 20W");
            Listing scarce = listing(shortId, 450, "Phone Charger");
            scarce.setStockQty(1);
            when(listingRepository.findAllById(any())).thenReturn(List.of(covered, scarce));
            when(coverage.findByListingIdIn(any()))
                    .thenReturn(List.of(new ListingDeliveryTown(shortId, "harare", 200)));
            when(addressService.requireForCheckout(any(), any())).thenReturn(savedAddress());

            ApiException ex = assertThrows(ApiException.class, () -> service.createOrder(BUYER,
                    new CreateOrderRequest("0771234567", null,
                            List.of(item(listingId, 1), item(shortId, 3)),
                            DeliveryMethod.DELIVERY, null, null), RAW_KEY));

            assertEquals("insufficient_stock", ex.code());
        }

        @Test
        void theDestinationIsSNAPSHOTontoTheOrder_notReferencedById() {
            // The buyer may rename, edit or delete the book entry the moment
            // after ordering; a parcel already packed must not follow it.
            oneSellableListing();
            DeliveryAddress chosen = savedAddress();
            when(addressService.requireForCheckout(any(), any())).thenReturn(chosen);

            service.createOrder(BUYER, new CreateOrderRequest("0771234567", null,
                    List.of(item(listingId, 1)), DeliveryMethod.DELIVERY, chosen.getId(), null),
                    RAW_KEY);

            MarketOrder row = createdRow();
            assertEquals("Tariro Moyo", row.getDeliveryRecipientName());
            assertEquals("+263771234567", row.getDeliveryRecipientMsisdn());
            assertEquals("14 Samora Machel Ave", row.getDeliveryLine1());
            assertEquals("Flat 3B", row.getDeliveryLine2());
            assertEquals("Harare", row.getDeliveryCity());
            assertEquals("Avondale", row.getDeliveryArea());
            assertEquals("Opposite the clinic", row.getDeliveryLandmark());
            // Kept only as provenance — nothing reads back through it.
            assertEquals(chosen.getId(), row.getDeliveryAddressId());
        }

        @Test
        void aDeliveryOrderWithNoAddressIsRefusedBeforeAnyStockIsTouched() {
            oneSellableListing();
            when(addressService.requireForCheckout(any(), any()))
                    .thenThrow(ApiException.badRequest("delivery_address_required",
                            "Choose a delivery address, or add one first"));

            ApiException ex = assertThrows(ApiException.class, () -> service.createOrder(BUYER,
                    new CreateOrderRequest("0771234567", null, List.of(item(listingId, 1)),
                            DeliveryMethod.DELIVERY, null, null), RAW_KEY));

            assertEquals("delivery_address_required", ex.code());
            verify(listingRepository, never()).reserveStock(any(UUID.class), anyInt());
        }

        @Test
        void orderLinesCarryTheSELLERasAtOrderTime() {
            // The fulfilment queue and the merchant notifier both group on this
            // snapshot; a later join back to the listing would name whoever owns
            // it now.
            Listing sold = listing(listingId, 1550, "Solar Lantern 20W");
            when(listingRepository.findAllById(any())).thenReturn(List.of(sold));
            when(listingRepository.reserveStock(any(UUID.class), anyInt())).thenReturn(1);

            service.createOrder(BUYER, req("0771234567", item(listingId, 1)), RAW_KEY);

            ArgumentCaptor<List<MarketOrderItem>> captor = ArgumentCaptor.forClass(List.class);
            verify(itemRepository).saveAll(captor.capture());
            assertEquals(sold.getMerchantId(), captor.getValue().getFirst().getMerchantId());
        }

        @Test
        void fromCartSourcesTheCartAndClearsOnlyWhatWasBought() {
            oneSellableListing();
            when(cartService.basketOf(BUYER))
                    .thenReturn(List.of(new BasketLine(listingId, 2)));

            OrderResponse response = service.createOrder(BUYER,
                    new CreateOrderRequest("0771234567", true, null, null, null, null), RAW_KEY);

            assertEquals(3100, response.totalCents());
            // Only the ordered listing leaves the cart; anything else stays.
            verify(cartService).removeOrdered(BUYER_UUID, List.of(listingId));
        }

        @Test
        void anExplicitItemsOrderNeverTouchesTheCart() {
            oneSellableListing();

            service.createOrder(BUYER, req("0771234567", item(listingId, 1)), RAW_KEY);

            verify(cartService, never()).removeOrdered(any(), any());
        }

        @Test
        void sendingBothACartFlagAndItemsIsRefused() {
            ApiException ex = createFails(new CreateOrderRequest("0771234567", true,
                    List.of(item(listingId, 1)), null, null, null));

            assertEquals("ambiguous_basket", ex.code());
        }

        @Test
        void aFailedOrderNeverClearsTheCart() {
            // The cart is cleared only AFTER the order commits — a shopper whose
            // order failed must not also lose what they were buying.
            when(cartService.basketOf(BUYER)).thenReturn(List.of(new BasketLine(listingId, 1)));
            when(listingRepository.findAllById(any())).thenReturn(List.of());

            assertThrows(ApiException.class, () -> service.createOrder(BUYER,
                    new CreateOrderRequest("0771234567", true, null, null, null, null), RAW_KEY));

            verify(cartService, never()).removeOrdered(any(), any());
        }

        @Test
        void aPendingOrderCarriesTheCallItMustMakeToBePaidFor() {
            oneSellableListing();

            OrderResponse response = service.createOrder(BUYER,
                    req("0771234567", item(listingId, 2)), RAW_KEY);

            assertNotNull(response.payment());
            // Cross-service knowledge the app no longer has to hardcode.
            assertEquals("POST /payments", response.payment().endpoint());
            assertEquals("MARKETPLACE", response.payment().orderType());
            assertEquals(response.orderRef(), response.payment().orderRef());
            assertEquals(response.totalCents(), response.payment().amountCents());
            assertEquals(response.expiresAt(), response.payment().payBefore());
            assertFalse(response.payment().methods().isEmpty());
        }

        @Test
        void aNewOrderHasNoParcelsYet() {
            oneSellableListing();

            OrderResponse response = service.createOrder(BUYER,
                    req("0771234567", item(listingId, 1)), RAW_KEY);

            // Nothing to pack until the money has moved.
            assertTrue(response.fulfilments().isEmpty());
            assertNull(response.fulfilmentStatus());
        }

        @Test
        void confirmingPaymentOpensTheSellersParcelsInTheSameTransaction() {
            MarketOrder order = order(OrderStatus.PENDING_PAYMENT);
            when(orderRepository.findByOrderRef(ORDER_REF)).thenReturn(Optional.of(order));

            service.confirmPayment(ORDER_REF, new ConfirmPaymentRequest("INB-PAY-1",
                    order.getTotalCents()));

            // An order that committed as paid with nothing on any seller's queue
            // would be money taken for goods nobody was asked to send.
            verify(fulfilmentService).openForOrder(order);
        }

        @Test
        void aRefusedConfirmOpensNothing() {
            MarketOrder order = order(OrderStatus.PENDING_PAYMENT);
            when(orderRepository.findByOrderRef(ORDER_REF)).thenReturn(Optional.of(order));

            assertThrows(ApiException.class, () -> service.confirmPayment(ORDER_REF,
                    new ConfirmPaymentRequest("INB-PAY-1", order.getTotalCents() * 100)));

            verify(fulfilmentService, never()).openForOrder(any());
        }
    }
}
