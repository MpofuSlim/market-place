package com.innbucks.marketplaceservice.fulfilment.tracking;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.order.OrderStatus;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins who may report a parcel's position, which reports are stored, and when
 * the buyer is shown one.
 */
class ParcelTrackingServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID BUYER_UUID = UUID.randomUUID();

    /** A STAFF driver: no seller scope, only the courier one. */
    private static final AuthenticatedUser DRIVER = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("COURIER"), null, null, null, "ZW",
            ORG.toString());
    private static final AuthenticatedUser OTHER_DRIVER = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("COURIER"), null, null, null, "ZW",
            OTHER_ORG.toString());
    private static final AuthenticatedUser NO_SCOPE = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("COURIER"), null, null, null, "ZW", null);
    private static final AuthenticatedUser BUYER = new AuthenticatedUser(
            BUYER_UUID.toString(), Set.of("CUSTOMER"), null, null, "+263771234567", "ZW");
    private static final AuthenticatedUser STRANGER = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("CUSTOMER"), null, null, "+263779999999", "ZW");

    private OrderFulfilmentRepository fulfilmentRepository;
    private MarketOrderRepository orderRepository;
    private MarketOrderItemRepository itemRepository;
    private SimpleMeterRegistry registry;
    private ParcelTrackingService service;

    @BeforeEach
    void setUp() {
        fulfilmentRepository = mock(OrderFulfilmentRepository.class);
        orderRepository = mock(MarketOrderRepository.class);
        itemRepository = mock(MarketOrderItemRepository.class);
        registry = new SimpleMeterRegistry();
        service = new ParcelTrackingService(fulfilmentRepository, orderRepository, itemRepository,
                new TrackingProperties(), new MarketplaceMetrics(registry),
                mock(com.innbucks.marketplaceservice.pickup.CollectionPointViews.class));
    }

    private MarketOrder order(DeliveryMethod method) {
        Instant now = Instant.now();
        MarketOrder order = MarketOrder.builder()
                .id(ORDER_ID).orderRef("MKT-4F9A1C22B7D3").buyerUuid(BUYER_UUID)
                .buyerMsisdn("+263771234567").status(OrderStatus.PAID)
                .subtotalCents(4798).deliveryFeeCents(800).totalCents(5598).currency("USD")
                .deliveryMethod(method)
                .deliveryRecipientName(method == DeliveryMethod.DELIVERY ? "Tariro Moyo" : null)
                .deliveryRecipientMsisdn(method == DeliveryMethod.DELIVERY ? "+263771234567" : null)
                .deliveryLine1(method == DeliveryMethod.DELIVERY ? "14 Samora Machel Ave" : null)
                .deliveryCity(method == DeliveryMethod.DELIVERY ? "Harare" : null)
                .expiresAt(now).paidAt(now).createdAt(now).updatedAt(now).build();
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private OrderFulfilment parcel(UUID merchantId, FulfilmentStatus status) {
        Instant now = Instant.now();
        OrderFulfilment parcel = OrderFulfilment.builder()
                .id(UUID.randomUUID()).orderId(ORDER_ID).merchantId(merchantId).status(status)
                .trackingCode("TRK-7F3K9Q2M4X")
                .dispatchedAt(status == FulfilmentStatus.DISPATCHED ? now : null)
                .createdAt(now.minusSeconds(3600)).updatedAt(now).version(0L).build();
        when(fulfilmentRepository.findById(parcel.getId())).thenReturn(Optional.of(parcel));
        return parcel;
    }

    private static LocationPingRequest harare(Instant at) {
        return new LocationPingRequest(-17.8292204, 31.0539612, 12, at);
    }

    private double pings(String outcome) {
        var counter = registry.find("marketplace.tracking.pings").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static String code(Throwable ex) {
        return ((ApiException) ex).code();
    }

    // ------------------------------------------------------------------
    // Reporting a position
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A driver's position for their business's parcel on the road is stored")
    void storesAPositionInTransit() {
        order(DeliveryMethod.DELIVERY);
        OrderFulfilment parcel = parcel(ORG, FulfilmentStatus.DISPATCHED);
        when(fulfilmentRepository.recordLocation(any(), any(), any(), any(), any(), anyString(),
                any())).thenReturn(1);
        Instant fix = Instant.now().minusSeconds(3);

        LocationPingResponse response = service.recordLocation(DRIVER, parcel.getId(), harare(fix));

        assertThat(response.accepted()).isTrue();
        assertThat(response.lastLocationAt()).isEqualTo(fix);
        ArgumentCaptor<Instant> notAfter = ArgumentCaptor.forClass(Instant.class);
        // Six decimal places — the column's own scale — and the throttle
        // window is measured back from the fix, not from the server clock.
        verify(fulfilmentRepository).recordLocation(eq(parcel.getId()),
                eq(new BigDecimal("-17.829220")), eq(new BigDecimal("31.053961")), eq(12),
                eq(fix), eq(DRIVER.uuid()), notAfter.capture());
        assertThat(notAfter.getValue()).isEqualTo(fix.minusSeconds(5));
        assertThat(pings("accepted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A report the database declines (too soon, or older than the stored fix) is a 200 accepted=false")
    void aDeclinedReportIsNotAnError() {
        order(DeliveryMethod.DELIVERY);
        OrderFulfilment parcel = parcel(ORG, FulfilmentStatus.DISPATCHED);
        Instant stored = Instant.now().minusSeconds(2);
        parcel.setLastLocationAt(stored);
        when(fulfilmentRepository.recordLocation(any(), any(), any(), any(), any(), anyString(),
                any())).thenReturn(0);

        LocationPingResponse response = service.recordLocation(DRIVER, parcel.getId(),
                harare(Instant.now()));

        assertThat(response.accepted()).isFalse();
        assertThat(response.lastLocationAt()).isEqualTo(stored);
        assertThat(pings("ignored")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A fix older than ten minutes is not where the parcel is now, and never reaches the database")
    void aStaleFixIsIgnored() {
        order(DeliveryMethod.DELIVERY);
        OrderFulfilment parcel = parcel(ORG, FulfilmentStatus.DISPATCHED);

        LocationPingResponse response = service.recordLocation(DRIVER, parcel.getId(),
                harare(Instant.now().minusSeconds(3600)));

        assertThat(response.accepted()).isFalse();
        verify(fulfilmentRepository, never()).recordLocation(any(), any(), any(), any(), any(),
                anyString(), any());
    }

    @Test
    @DisplayName("A phone clock running ahead is treated as now, never stored in the future")
    void aFutureFixIsClampedToNow() {
        order(DeliveryMethod.DELIVERY);
        OrderFulfilment parcel = parcel(ORG, FulfilmentStatus.DISPATCHED);
        when(fulfilmentRepository.recordLocation(any(), any(), any(), any(), any(), anyString(),
                any())).thenReturn(1);
        Instant before = Instant.now();

        LocationPingResponse response = service.recordLocation(DRIVER, parcel.getId(),
                harare(Instant.now().plusSeconds(7200)));

        assertThat(response.lastLocationAt()).isBetween(before, Instant.now());
    }

    @Test
    @DisplayName("Another business's parcel is the same 404 as one that does not exist")
    void anotherBusinessesParcelIsNotFound() {
        order(DeliveryMethod.DELIVERY);
        OrderFulfilment parcel = parcel(ORG, FulfilmentStatus.DISPATCHED);

        assertThatThrownBy(() -> service.recordLocation(OTHER_DRIVER, parcel.getId(),
                harare(Instant.now())))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("fulfilment_not_found"));
        assertThatThrownBy(() -> service.recordLocation(DRIVER, UUID.randomUUID(),
                harare(Instant.now())))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("fulfilment_not_found"));
    }

    @Test
    @DisplayName("Only a DELIVERY parcel on the road takes positions: not a collection, not one still being packed")
    void onlyADeliveryInTransitTakesPositions() {
        order(DeliveryMethod.COLLECTION);
        OrderFulfilment atTheCounter = parcel(ORG, FulfilmentStatus.DISPATCHED);
        assertThatThrownBy(() -> service.recordLocation(DRIVER, atTheCounter.getId(),
                harare(Instant.now())))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("parcel_not_in_transit"));

        order(DeliveryMethod.DELIVERY);
        for (FulfilmentStatus status : List.of(FulfilmentStatus.PREPARING,
                FulfilmentStatus.DELIVERED, FulfilmentStatus.UNFULFILLED)) {
            OrderFulfilment parcel = parcel(ORG, status);
            assertThatThrownBy(() -> service.recordLocation(DRIVER, parcel.getId(),
                    harare(Instant.now())))
                    .satisfies(ex -> assertThat(code(ex)).isEqualTo("parcel_not_in_transit"));
        }
        verify(fulfilmentRepository, never()).recordLocation(any(), any(), any(), any(), any(),
                anyString(), any());
    }

    @Test
    @DisplayName("A phone with no fix yet (0,0) is outside the market and refused")
    void outOfBoundsIsRefused() {
        order(DeliveryMethod.DELIVERY);
        OrderFulfilment parcel = parcel(ORG, FulfilmentStatus.DISPATCHED);

        assertThatThrownBy(() -> service.recordLocation(DRIVER, parcel.getId(),
                new LocationPingRequest(0.0, 0.0, null, null)))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("location_out_of_bounds"));
        // Johannesburg: a real place, just not this market.
        assertThatThrownBy(() -> service.recordLocation(DRIVER, parcel.getId(),
                new LocationPingRequest(-26.2041, 28.0473, null, null)))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("location_out_of_bounds"));
    }

    @Test
    @DisplayName("A caller with no delivering business is refused before anything is read")
    void noCourierScopeIsForbidden() {
        assertThatThrownBy(() -> service.recordLocation(NO_SCOPE, UUID.randomUUID(),
                harare(Instant.now())))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("courier_scope_missing"));
        assertThatThrownBy(() -> service.courierRun(NO_SCOPE, 0, 20))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("courier_scope_missing"));
        verify(fulfilmentRepository, never()).findById(any());
    }

    // ------------------------------------------------------------------
    // The run
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The run carries only this business's lines of each order, and is capped at 100")
    void runCarriesOnlyThisBusinesssLines() {
        MarketOrder order = order(DeliveryMethod.DELIVERY);
        OrderFulfilment parcel = parcel(ORG, FulfilmentStatus.DISPATCHED);
        when(fulfilmentRepository.findRun(eq(ORG), eq(FulfilmentStatus.DISPATCHED),
                eq(DeliveryMethod.DELIVERY), any())).thenReturn(List.of(parcel));
        when(orderRepository.findAllById(any())).thenReturn(List.of(order));
        when(itemRepository.findByOrderIdIn(any())).thenReturn(List.of(
                MarketOrderItem.builder().id(UUID.randomUUID()).orderId(ORDER_ID)
                        .listingId(UUID.randomUUID()).merchantId(ORG)
                        .titleSnapshot("Wireless Bluetooth Speaker").unitPriceCents(2399)
                        .quantity(2).lineTotalCents(4798).build(),
                MarketOrderItem.builder().id(UUID.randomUUID()).orderId(ORDER_ID)
                        .listingId(UUID.randomUUID()).merchantId(OTHER_ORG)
                        .titleSnapshot("Garden Hose").unitPriceCents(2599)
                        .quantity(1).lineTotalCents(2599).build()));

        List<CourierParcelResponse> run = service.courierRun(DRIVER, 0, 500);

        assertThat(run).singleElement().satisfies(stop -> {
            assertThat(stop.trackingCode()).isEqualTo("TRK-7F3K9Q2M4X");
            assertThat(stop.destination().line1()).isEqualTo("14 Samora Machel Ave");
            assertThat(stop.items()).extracting(CourierParcelResponse.Item::title)
                    .containsExactly("Wireless Bluetooth Speaker");
        });
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(fulfilmentRepository).findRun(eq(ORG), any(), any(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(100);
    }

    // ------------------------------------------------------------------
    // The buyer's screen
    // ------------------------------------------------------------------

    @Test
    @DisplayName("While a delivery is on the road the buyer sees the courier's last position")
    void buyerSeesTheLivePositionInTransit() {
        order(DeliveryMethod.DELIVERY);
        OrderFulfilment parcel = parcel(ORG, FulfilmentStatus.DISPATCHED);
        Instant fix = Instant.now().minusSeconds(30);
        parcel.setLastLatitude(new BigDecimal("-17.829220"));
        parcel.setLastLongitude(new BigDecimal("31.053961"));
        parcel.setLastAccuracyMeters(12);
        parcel.setLastLocationAt(fix);

        ParcelTrackingResponse tracking = service.buyerTracking(BUYER, ORDER_ID, parcel.getId());

        assertThat(tracking.trackingStatus()).isEqualTo(TrackingStatus.DISPATCHED);
        assertThat(tracking.timeline()).extracting(ParcelTrackingResponse.Stage::status)
                .containsExactly(TrackingStatus.RECEIVED, TrackingStatus.DISPATCHED);
        assertThat(tracking.liveLocation()).isNotNull();
        assertThat(tracking.liveLocation().latitude()).isEqualTo(-17.82922);
        assertThat(tracking.liveLocation().recordedAt()).isEqualTo(fix);
    }

    @Test
    @DisplayName("Once delivered the pin disappears - the last point is usually the buyer's own door")
    void noPositionAfterDelivery() {
        order(DeliveryMethod.DELIVERY);
        OrderFulfilment parcel = parcel(ORG, FulfilmentStatus.DELIVERED);
        parcel.setDeliveredAt(Instant.now());
        parcel.setLastLatitude(new BigDecimal("-17.829220"));
        parcel.setLastLongitude(new BigDecimal("31.053961"));
        parcel.setLastLocationAt(Instant.now());

        ParcelTrackingResponse tracking = service.buyerTracking(BUYER, ORDER_ID, parcel.getId());

        assertThat(tracking.trackingStatus()).isEqualTo(TrackingStatus.DELIVERED);
        assertThat(tracking.liveLocation()).isNull();
    }

    @Test
    @DisplayName("A cancelled parcel reads CANCELLED with the seller's reason")
    void cancelledCarriesTheReason() {
        order(DeliveryMethod.DELIVERY);
        OrderFulfilment parcel = parcel(ORG, FulfilmentStatus.UNFULFILLED);
        parcel.setUnfulfilledAt(Instant.now());
        parcel.setUnfulfilledReason("Out of stock");

        ParcelTrackingResponse tracking = service.buyerTracking(BUYER, ORDER_ID, parcel.getId());

        assertThat(tracking.trackingStatus()).isEqualTo(TrackingStatus.CANCELLED);
        assertThat(tracking.cancelledReason()).isEqualTo("Out of stock");
        assertThat(tracking.timeline()).extracting(ParcelTrackingResponse.Stage::status)
                .containsExactly(TrackingStatus.RECEIVED, TrackingStatus.CANCELLED);
    }

    @Test
    @DisplayName("Someone else's order, or a parcel named under the wrong order, is a plain 404")
    void buyerTrackingIsOwnerMasked() {
        order(DeliveryMethod.DELIVERY);
        OrderFulfilment parcel = parcel(ORG, FulfilmentStatus.DISPATCHED);

        assertThatThrownBy(() -> service.buyerTracking(STRANGER, ORDER_ID, parcel.getId()))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("fulfilment_not_found"));
        assertThatThrownBy(() -> service.buyerTracking(BUYER, UUID.randomUUID(), parcel.getId()))
                .satisfies(ex -> assertThat(code(ex)).isEqualTo("fulfilment_not_found"));
    }
}
