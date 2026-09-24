package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.dto.DispatchRequest;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentResponse;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderEvent;
import com.innbucks.marketplaceservice.order.MarketOrderEventRepository;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.order.OrderStatus;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.order.MarketOrderDeliveryFee;
import com.innbucks.marketplaceservice.order.MarketOrderDeliveryFeeRepository;
import com.innbucks.marketplaceservice.support.TestParcelViews;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins fulfilment's three rules: one parcel per SELLER (opened idempotently
 * with the payment), a lifecycle that never rewrites a terminal state, and
 * scoping that lets no seller see or touch another's parcel.
 */
class FulfilmentServiceTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID MERCHANT_A = UUID.randomUUID();
    private static final UUID MERCHANT_B = UUID.randomUUID();
    private static final UUID BUYER_UUID = UUID.randomUUID();

    private static final AuthenticatedUser SELLER_A = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("MERCHANT_ADMIN"),
            MERCHANT_A.toString(), null, null, "ZW");
    private static final AuthenticatedUser SELLER_B = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("MERCHANT_ADMIN"),
            MERCHANT_B.toString(), null, null, "ZW");
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("SUPER_ADMIN"), null, null, null, "ZW");
    private static final AuthenticatedUser BUYER = new AuthenticatedUser(
            BUYER_UUID.toString(), Set.of("CUSTOMER"), null, null, "+263771234567", "ZW");

    private OrderFulfilmentRepository fulfilmentRepository;
    private MarketOrderRepository orderRepository;
    private MarketOrderItemRepository itemRepository;
    private MarketOrderEventRepository eventRepository;
    private AuditService auditService;
    private com.innbucks.marketplaceservice.settlement.SettlementService settlementService;
    private SimpleMeterRegistry registry;
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    private MarketOrderDeliveryFeeRepository deliveryFees;
    private FulfilmentService service;

    @BeforeEach
    void setUp() {
        fulfilmentRepository = mock(OrderFulfilmentRepository.class);
        orderRepository = mock(MarketOrderRepository.class);
        itemRepository = mock(MarketOrderItemRepository.class);
        eventRepository = mock(MarketOrderEventRepository.class);
        auditService = mock(AuditService.class);
        registry = new SimpleMeterRegistry();
        settlementService = mock(com.innbucks.marketplaceservice.settlement.SettlementService.class);
        eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
        deliveryFees = mock(MarketOrderDeliveryFeeRepository.class);
        service = new FulfilmentService(fulfilmentRepository, orderRepository, itemRepository,
                eventRepository, settlementService, auditService, new MarketplaceMetrics(registry),
                mock(com.innbucks.marketplaceservice.fulfilment.collect.CollectCodeAttempts.class),
                mock(com.innbucks.marketplaceservice.notify.CollectCodeNotifier.class),
                mock(ParcelStockReturner.class),
                eventPublisher, deliveryFees,
                TestParcelViews.over(orderRepository, itemRepository, settlementService),
                mock(com.innbucks.marketplaceservice.pickup.CollectionPointViews.class),
                new com.innbucks.marketplaceservice.fulfilment.BuyerParcelRules(7));
        when(fulfilmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order()));
        when(itemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(
                item(MERCHANT_A, "Solar Lantern 20W", 2, 3100),
                item(MERCHANT_B, "Garden Hose", 1, 2599)));
    }

    private static MarketOrder order() {
        Instant now = Instant.now();
        return MarketOrder.builder()
                .id(ORDER_ID).orderRef("MKT-4F9A1C22B7D3").buyerUuid(BUYER_UUID)
                .buyerMsisdn("+263771234567").status(OrderStatus.PAID)
                .subtotalCents(5699).deliveryFeeCents(200).totalCents(5899).currency("USD")
                .deliveryMethod(DeliveryMethod.DELIVERY)
                .deliveryRecipientName("Tariro Moyo").deliveryRecipientMsisdn("+263771234567")
                .deliveryLine1("14 Samora Machel Ave").deliveryCity("Harare")
                .expiresAt(now.plusSeconds(1800)).paidAt(now)
                .createdAt(now).updatedAt(now).build();
    }

    private static MarketOrderItem item(UUID merchantId, String title, int qty, long lineTotal) {
        return MarketOrderItem.builder()
                .id(UUID.randomUUID()).orderId(ORDER_ID).listingId(UUID.randomUUID())
                .merchantId(merchantId).titleSnapshot(title)
                .unitPriceCents(lineTotal / qty).quantity(qty).lineTotalCents(lineTotal).build();
    }

    private OrderFulfilment parcel(UUID id, UUID merchantId, FulfilmentStatus status) {
        Instant now = Instant.now();
        OrderFulfilment p = OrderFulfilment.builder()
                .id(id).orderId(ORDER_ID).merchantId(merchantId).status(status)
                .createdAt(now).updatedAt(now).version(0L).build();
        when(fulfilmentRepository.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    private double outcome(String tag) {
        var counter = registry.find("marketplace.fulfilments").tag("outcome", tag).counter();
        return counter == null ? 0.0 : counter.count();
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    @Test
    @DisplayName("One parcel per DISTINCT seller in the order")
    void opensOneParcelPerSeller() {
        when(fulfilmentRepository.openIfAbsent(any(), any(), any(), anyLong(), any(), any())).thenReturn(1);

        service.openForOrder(order());

        verify(fulfilmentRepository).openIfAbsent(any(), eq(ORDER_ID), eq(MERCHANT_A), anyLong(), any(), any());
        verify(fulfilmentRepository).openIfAbsent(any(), eq(ORDER_ID), eq(MERCHANT_B), anyLong(), any(), any());
        assertThat(outcome("opened")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Two lines from ONE seller become ONE parcel")
    void groupsASellersLinesIntoOneParcel() {
        when(itemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(
                item(MERCHANT_A, "Solar Lantern 20W", 2, 3100),
                item(MERCHANT_A, "Torch", 1, 1000)));
        when(fulfilmentRepository.openIfAbsent(any(), any(), any(), anyLong(), any(), any())).thenReturn(1);

        service.openForOrder(order());

        verify(fulfilmentRepository, times(1)).openIfAbsent(any(), any(), eq(MERCHANT_A), anyLong(), any(), any());
    }

    @Test
    @DisplayName("A replayed payment confirm opens nothing a second time and journals nothing")
    void openingIsIdempotent() {
        // The payments service is free to replay a confirm; a seller's queue
        // must not double.
        when(fulfilmentRepository.openIfAbsent(any(), any(), any(), anyLong(), any(), any())).thenReturn(0);

        service.openForOrder(order());

        verify(eventRepository, never()).save(any());
        assertThat(outcome("opened")).isZero();
    }

    @Test
    @DisplayName("Opening journals onto the ORDER's own history, tagged FULFILMENT")
    void openingJournalsAgainstTheOrder() {
        when(fulfilmentRepository.openIfAbsent(any(), any(), any(), anyLong(), any(), any())).thenReturn(1);

        service.openForOrder(order());

        ArgumentCaptor<MarketOrderEvent> captor = ArgumentCaptor.forClass(MarketOrderEvent.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getKind()).isEqualTo(MarketOrderEvent.KIND_FULFILMENT);
        assertThat(captor.getValue().getOrderId()).isEqualTo(ORDER_ID);
        assertThat(captor.getValue().getFromStatus()).isNull();
        assertThat(captor.getValue().getToStatus()).isEqualTo("PREPARING");
    }

    // ------------------------------------------------------------------
    // Roll-up
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The order-level roll-up is the LEAST advanced parcel")
    void rollUpTakesTheLeastAdvanced() {
        // "Delivered" must mean everything arrived — rolling up to the
        // furthest-along parcel would tell a buyer with two sellers their order
        // had landed while half of it was still in a warehouse.
        assertThat(FulfilmentService.rollUp(List.of(
                parcel(UUID.randomUUID(), MERCHANT_A, FulfilmentStatus.DELIVERED),
                parcel(UUID.randomUUID(), MERCHANT_B, FulfilmentStatus.PREPARING))))
                .isEqualTo(FulfilmentStatus.PREPARING);
    }

    @Test
    @DisplayName("All parcels delivered rolls up to DELIVERED")
    void rollUpOfAllDelivered() {
        assertThat(FulfilmentService.rollUp(List.of(
                parcel(UUID.randomUUID(), MERCHANT_A, FulfilmentStatus.DELIVERED),
                parcel(UUID.randomUUID(), MERCHANT_B, FulfilmentStatus.DELIVERED))))
                .isEqualTo(FulfilmentStatus.DELIVERED);
    }

    @Test
    @DisplayName("No parcels rolls up to NULL, not to PREPARING")
    void rollUpOfNothingIsNull() {
        // An unpaid, cancelled or expired order has nothing to fulfil; a
        // PREPARING there would claim a seller was packing goods nobody has
        // paid for.
        assertThat(FulfilmentService.rollUp(List.of())).isNull();
    }

    // ------------------------------------------------------------------
    // Transitions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Dispatching records the note, stamps the time, journals and audits")
    void dispatchRecordsEverything() {
        UUID id = UUID.randomUUID();
        OrderFulfilment p = parcel(id, MERCHANT_A, FulfilmentStatus.PREPARING);

        MerchantFulfilmentResponse view =
                service.dispatch(SELLER_A, id, new DispatchRequest("Swift Couriers, waybill 88213"));

        assertThat(p.getStatus()).isEqualTo(FulfilmentStatus.DISPATCHED);
        assertThat(p.getDispatchNote()).isEqualTo("Swift Couriers, waybill 88213");
        assertThat(p.getDispatchedAt()).isNotNull();
        assertThat(view.status()).isEqualTo(FulfilmentStatus.DISPATCHED);
        verify(auditService).record(eq(AuditEventType.FULFILMENT_DISPATCHED), anyString(),
                eq(id.toString()), anyMap());
        assertThat(outcome("dispatched")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A dispatch note is sanitized, and a blank one stays null")
    void dispatchNoteIsSanitized() {
        UUID id = UUID.randomUUID();
        OrderFulfilment p = parcel(id, MERCHANT_A, FulfilmentStatus.PREPARING);

        service.dispatch(SELLER_A, id, new DispatchRequest("<b>Swift</b> Couriers"));
        assertThat(p.getDispatchNote()).isEqualTo("Swift Couriers");

        OrderFulfilment q = parcel(UUID.randomUUID(), MERCHANT_A, FulfilmentStatus.PREPARING);
        service.dispatch(SELLER_A, q.getId(), new DispatchRequest("   "));
        assertThat(q.getDispatchNote()).isNull();
    }

    @Test
    @DisplayName("Dispatching with no body at all is allowed")
    void dispatchWithoutABodyIsAllowed() {
        UUID id = UUID.randomUUID();
        OrderFulfilment p = parcel(id, MERCHANT_A, FulfilmentStatus.PREPARING);

        service.dispatch(SELLER_A, id, null);

        assertThat(p.getStatus()).isEqualTo(FulfilmentStatus.DISPATCHED);
        assertThat(p.getDispatchNote()).isNull();
    }

    @Test
    @DisplayName("A seller may close a parcel themselves, recorded as MERCHANT")
    void sellerCanCloseTheParcel() {
        // A buyer who never opens the app must not leave a parcel open forever.
        UUID id = UUID.randomUUID();
        OrderFulfilment p = parcel(id, MERCHANT_A, FulfilmentStatus.DISPATCHED);

        service.markDelivered(SELLER_A, id);

        assertThat(p.getStatus()).isEqualTo(FulfilmentStatus.DELIVERED);
        assertThat(p.getDeliveredBy()).isEqualTo(DeliveryConfirmer.MERCHANT);
        assertThat(p.getDeliveredAt()).isNotNull();
    }

    @Test
    @DisplayName("A buyer's own confirmation is recorded as BUYER — the stronger record")
    void buyerConfirmationIsRecordedAsSuch() {
        UUID id = UUID.randomUUID();
        OrderFulfilment p = parcel(id, MERCHANT_A, FulfilmentStatus.DISPATCHED);

        service.confirmReceived(BUYER, id);

        assertThat(p.getDeliveredBy()).isEqualTo(DeliveryConfirmer.BUYER);
        verify(auditService).record(eq(AuditEventType.FULFILMENT_DELIVERED), eq(BUYER.uuid()),
                eq(id.toString()), anyMap());
    }

    @Test
    @DisplayName("A repeat close is refused and COUNTED, never re-applied")
    void terminalParcelsAreImmutable() {
        UUID id = UUID.randomUUID();
        OrderFulfilment p = parcel(id, MERCHANT_A, FulfilmentStatus.DELIVERED);
        Instant closedAt = p.getDeliveredAt();

        assertThatThrownBy(() -> service.markDelivered(SELLER_A, id))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("illegal_fulfilment_state");
        assertThat(p.getDeliveredAt()).isEqualTo(closedAt);
        assertThat(outcome("illegal_transition")).isEqualTo(1.0);
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("A buyer's \"received\" on a parcel the seller already closed is refused AND "
            + "counted - the race the illegal-transition counter exists to show")
    void aRefusedBuyerConfirmationIsCounted() {
        UUID id = UUID.randomUUID();
        OrderFulfilment p = parcel(id, MERCHANT_A, FulfilmentStatus.DELIVERED);
        p.setDeliveredBy(DeliveryConfirmer.MERCHANT);

        assertThatThrownBy(() -> service.confirmReceived(BUYER, id))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    assertThat(((ApiException) ex).code()).isEqualTo("illegal_fulfilment_state");
                    assertThat(ex.getMessage())
                            .isEqualTo("This parcel is DELIVERED and cannot move to DELIVERED");
                });
        assertThat(p.getDeliveredBy()).isEqualTo(DeliveryConfirmer.MERCHANT);
        assertThat(outcome("illegal_transition")).isEqualTo(1.0);
        verify(eventRepository, never()).save(any());
    }

    @Test
    @DisplayName("A delivered parcel cannot be dispatched again")
    void deliveredCannotBeDispatched() {
        UUID id = UUID.randomUUID();
        parcel(id, MERCHANT_A, FulfilmentStatus.DELIVERED);

        assertThatThrownBy(() -> service.dispatch(SELLER_A, id, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("illegal_fulfilment_state");
    }

    // ------------------------------------------------------------------
    // The collection handover rule
    // ------------------------------------------------------------------

    private MarketOrder collectionOrder() {
        MarketOrder collection = order();
        collection.setDeliveryMethod(DeliveryMethod.COLLECTION);
        collection.setDeliveryLine1(null);
        collection.setDeliveryRecipientName(null);
        collection.setDeliveryRecipientMsisdn(null);
        collection.setDeliveryCity(null);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(collection));
        return collection;
    }

    @Test
    @DisplayName("A seller cannot close a COLLECTION as delivered on their own word")
    void collectionCannotBeSelfClosed() {
        // "The buyer collected" is the one claim a seller could make about a
        // parcel still on their shelf. Only the buyer's code or the buyer's own
        // confirmation closes a collection.
        collectionOrder();
        UUID id = UUID.randomUUID();
        OrderFulfilment p = parcel(id, MERCHANT_A, FulfilmentStatus.DISPATCHED);

        assertThatThrownBy(() -> service.markDelivered(SELLER_A, id))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("collect_code_required");

        assertThat(p.getStatus()).isEqualTo(FulfilmentStatus.DISPATCHED);
        assertThat(p.getDeliveredAt()).isNull();
        verify(settlementService, never()).onParcelDelivered(any());
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(outcome("self_close_refused")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("SUPER_ADMIN cannot close a COLLECTION as delivered either")
    void collectionCannotBeClosedByAnOperatorEither() {
        // An operator marking a counter handover they did not witness is the
        // same unprovable claim.
        collectionOrder();
        UUID id = UUID.randomUUID();
        OrderFulfilment p = parcel(id, MERCHANT_A, FulfilmentStatus.PREPARING);

        assertThatThrownBy(() -> service.markDelivered(ADMIN, id))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("collect_code_required");
        assertThat(p.getStatus()).isEqualTo(FulfilmentStatus.PREPARING);
    }

    @Test
    @DisplayName("The buyer's own confirmation still closes a COLLECTION")
    void buyerMayStillConfirmACollection() {
        collectionOrder();
        UUID id = UUID.randomUUID();
        OrderFulfilment p = parcel(id, MERCHANT_A, FulfilmentStatus.DISPATCHED);

        service.confirmReceived(BUYER, id);

        assertThat(p.getStatus()).isEqualTo(FulfilmentStatus.DELIVERED);
        assertThat(p.getDeliveredBy()).isEqualTo(DeliveryConfirmer.BUYER);
    }

    // ------------------------------------------------------------------
    // Telling the buyer
    // ------------------------------------------------------------------

    private ParcelProgressed publishedProgress() {
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(1)).publishEvent(events.capture());
        assertThat(events.getValue()).isInstanceOf(ParcelProgressed.class);
        return (ParcelProgressed) events.getValue();
    }

    @Test
    @DisplayName("Dispatching tells the buyer, naming the order and how it is coming")
    void dispatchTellsTheBuyer() {
        UUID id = UUID.randomUUID();
        parcel(id, MERCHANT_A, FulfilmentStatus.PREPARING);

        service.dispatch(SELLER_A, id, null);

        ParcelProgressed event = publishedProgress();
        assertThat(event.orderRef()).isEqualTo("MKT-4F9A1C22B7D3");
        assertThat(event.buyerMsisdn()).isEqualTo("+263771234567");
        assertThat(event.deliveryMethod()).isEqualTo(DeliveryMethod.DELIVERY);
        assertThat(event.status()).isEqualTo(FulfilmentStatus.DISPATCHED);
        assertThat(event.partOfOrder()).isFalse();
    }

    @Test
    @DisplayName("A seller's own 'delivered' tells the buyer, so they know the dispute window is running")
    void sellerCloseTellsTheBuyer() {
        UUID id = UUID.randomUUID();
        OrderFulfilment mine = parcel(id, MERCHANT_A, FulfilmentStatus.DISPATCHED);
        OrderFulfilment theirs = parcel(UUID.randomUUID(), MERCHANT_B, FulfilmentStatus.PREPARING);
        when(fulfilmentRepository.findByOrderIdOrderByCreatedAtAsc(ORDER_ID))
                .thenReturn(List.of(mine, theirs));

        service.markDelivered(SELLER_A, id);

        ParcelProgressed event = publishedProgress();
        assertThat(event.status()).isEqualTo(FulfilmentStatus.DELIVERED);
        // Two sellers: the copy says "part of your order", and only then.
        assertThat(event.partOfOrder()).isTrue();
    }

    @Test
    @DisplayName("A buyer's own confirmation tells nobody — they already know")
    void buyerConfirmationSendsNothing() {
        UUID id = UUID.randomUUID();
        parcel(id, MERCHANT_A, FulfilmentStatus.DISPATCHED);

        service.confirmReceived(BUYER, id);

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("A refused move tells nobody")
    void refusedMoveSendsNothing() {
        UUID id = UUID.randomUUID();
        parcel(id, MERCHANT_A, FulfilmentStatus.DELIVERED);

        assertThatThrownBy(() -> service.dispatch(SELLER_A, id, null))
                .isInstanceOf(ApiException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ------------------------------------------------------------------
    // Scoping
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Another seller's parcel is the same 404 as a nonexistent one")
    void foreignParcelsAre404() {
        // A seller must not be able to probe for other sellers' parcel ids.
        UUID id = UUID.randomUUID();
        parcel(id, MERCHANT_A, FulfilmentStatus.PREPARING);

        assertThatThrownBy(() -> service.dispatch(SELLER_B, id, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("fulfilment_not_found");
    }

    @Test
    @DisplayName("SUPER_ADMIN may act on any seller's parcel")
    void adminMayActOnAnyParcel() {
        UUID id = UUID.randomUUID();
        OrderFulfilment p = parcel(id, MERCHANT_A, FulfilmentStatus.PREPARING);

        service.dispatch(ADMIN, id, null);

        assertThat(p.getStatus()).isEqualTo(FulfilmentStatus.DISPATCHED);
    }

    @Test
    @DisplayName("A merchant token with no merchant scope is a 403, not a 500")
    void missingMerchantScopeIsForbidden() {
        UUID id = UUID.randomUUID();
        parcel(id, MERCHANT_A, FulfilmentStatus.PREPARING);
        AuthenticatedUser scopeless = new AuthenticatedUser(UUID.randomUUID().toString(),
                Set.of("MERCHANT_ADMIN"), null, null, null, "ZW");

        assertThatThrownBy(() -> service.dispatch(scopeless, id, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("merchant_scope_missing");
    }

    @Test
    @DisplayName("Another buyer's parcel cannot be confirmed received")
    void confirmReceivedIsOwnerScoped() {
        UUID id = UUID.randomUUID();
        parcel(id, MERCHANT_A, FulfilmentStatus.DISPATCHED);
        AuthenticatedUser otherBuyer = new AuthenticatedUser(UUID.randomUUID().toString(),
                Set.of("CUSTOMER"), null, null, null, "ZW");

        assertThatThrownBy(() -> service.confirmReceived(otherBuyer, id))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("fulfilment_not_found");
    }

    // ------------------------------------------------------------------
    // The seller's view
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A seller sees ONLY their own lines and subtotal, never the order total")
    void sellerViewIsScopedToTheirLines() {
        UUID id = UUID.randomUUID();
        parcel(id, MERCHANT_A, FulfilmentStatus.PREPARING);

        MerchantFulfilmentResponse view = service.markDelivered(SELLER_A, id);

        // The order holds 3100 (A) + 2599 (B) + 200 delivery = 5899. Seller A
        // learns only their own 3100.
        assertThat(view.items()).hasSize(1);
        assertThat(view.items().getFirst().titleSnapshot()).isEqualTo("Solar Lantern 20W");
        assertThat(view.subtotalCents()).isEqualTo(3100);
        assertThat(view.orderRef()).isEqualTo("MKT-4F9A1C22B7D3");
    }

    @Test
    @DisplayName("A DELIVERY parcel carries the destination the seller has to ship to")
    void sellerViewCarriesTheDestination() {
        UUID id = UUID.randomUUID();
        parcel(id, MERCHANT_A, FulfilmentStatus.PREPARING);

        MerchantFulfilmentResponse view = service.dispatch(SELLER_A, id, null);

        assertThat(view.deliveryMethod()).isEqualTo(DeliveryMethod.DELIVERY);
        assertThat(view.destination()).isNotNull();
        assertThat(view.destination().line1()).isEqualTo("14 Samora Machel Ave");
        assertThat(view.destination().recipientMsisdn()).isEqualTo("+263771234567");
    }

    @Test
    @DisplayName("A COLLECTION parcel carries no destination")
    void collectionParcelHasNoDestination() {
        MarketOrder collection = order();
        collection.setDeliveryMethod(DeliveryMethod.COLLECTION);
        collection.setDeliveryLine1(null);
        collection.setDeliveryRecipientName(null);
        collection.setDeliveryRecipientMsisdn(null);
        collection.setDeliveryCity(null);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(collection));
        UUID id = UUID.randomUUID();
        parcel(id, MERCHANT_A, FulfilmentStatus.PREPARING);

        assertThat(service.dispatch(SELLER_A, id, null).destination()).isNull();
    }

    // ------------------------------------------------------------------
    // Delivery fee + tracking code (V14)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Each parcel is opened with ITS seller's fee from the order, and a fresh tracking code")
    void openingCarriesTheSellersFeeAndATrackingCode() {
        when(deliveryFees.findByOrderId(ORDER_ID)).thenReturn(List.of(
                new MarketOrderDeliveryFee(ORDER_ID, MERCHANT_A, 300)));
        when(fulfilmentRepository.openIfAbsent(any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(1);

        service.openForOrder(order());

        ArgumentCaptor<String> codeA = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> codeB = ArgumentCaptor.forClass(String.class);
        verify(fulfilmentRepository).openIfAbsent(any(), eq(ORDER_ID), eq(MERCHANT_A), eq(300L),
                codeA.capture(), any());
        // No fee row = collection, or a seller who delivers free: never a guess.
        verify(fulfilmentRepository).openIfAbsent(any(), eq(ORDER_ID), eq(MERCHANT_B), eq(0L),
                codeB.capture(), any());
        assertThat(codeA.getValue()).matches("TRK-[0-9A-HJKMNP-TV-Z]{10}");
        assertThat(codeB.getValue()).isNotEqualTo(codeA.getValue());
    }

    private OrderFulfilment tracked(UUID merchantId, String code) {
        OrderFulfilment p = parcel(UUID.randomUUID(), merchantId, FulfilmentStatus.DISPATCHED);
        p.setTrackingCode(code);
        when(fulfilmentRepository.findByTrackingCode(code)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    @DisplayName("A seller finds their own parcel by code, however it was typed")
    void sellerFindsOwnParcelByCode() {
        tracked(MERCHANT_A, "TRK-7F3K9Q2M4X");

        MerchantFulfilmentResponse found = service.byTrackingCode(SELLER_A, " trk 7f3k-9q2m-4x ");

        assertThat(found.trackingCode()).isEqualTo("TRK-7F3K9Q2M4X");
    }

    @Test
    @DisplayName("Another seller's code is the same 404 as a code that does not exist")
    void anotherSellersCodeIsNotFound() {
        tracked(MERCHANT_A, "TRK-7F3K9Q2M4X");

        assertThatThrownBy(() -> service.byTrackingCode(SELLER_B, "TRK-7F3K9Q2M4X"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("fulfilment_not_found");
        assertThatThrownBy(() -> service.byTrackingCode(SELLER_B, "TRK-0000000000"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("fulfilment_not_found");
    }

    @Test
    @DisplayName("SUPER_ADMIN finds any seller's parcel; garbage never reaches the database")
    void adminFindsAnyAndGarbageIsNotFound() {
        tracked(MERCHANT_B, "TRK-7F3K9Q2M4X");

        assertThat(service.byTrackingCode(ADMIN, "TRK-7F3K9Q2M4X").merchantId())
                .isEqualTo(MERCHANT_B);
        assertThatThrownBy(() -> service.byTrackingCode(ADMIN, "not a code"))
                .isInstanceOf(ApiException.class);
        verify(fulfilmentRepository, times(1)).findByTrackingCode(anyString());
    }
}
