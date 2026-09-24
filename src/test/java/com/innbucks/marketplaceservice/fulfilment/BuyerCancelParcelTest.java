package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.collect.CollectCodeAttempts;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.notify.CollectCodeNotifier;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderDeliveryFeeRepository;
import com.innbucks.marketplaceservice.order.MarketOrderEventRepository;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.order.OrderStatus;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.settlement.MerchantSettlement;
import com.innbucks.marketplaceservice.settlement.SettlementService;
import com.innbucks.marketplaceservice.settlement.SettlementStatus;
import com.innbucks.marketplaceservice.support.TestParcelViews;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the buyer's way out of a paid parcel the seller has not sent (V16): the
 * same three moves as a seller's decline, recorded as the BUYER's, and refused
 * — before anything moves — whenever a refund could not honestly be queued.
 */
class BuyerCancelParcelTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.randomUUID();
    private static final UUID BUYER_UUID = UUID.randomUUID();
    private static final AuthenticatedUser BUYER = new AuthenticatedUser(
            BUYER_UUID.toString(), Set.of("CUSTOMER"), null, null, "+263771234567", "ZW");
    private static final AuthenticatedUser SOMEONE_ELSE = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("CUSTOMER"), null, null, "+263772222222", "ZW");

    private OrderFulfilmentRepository fulfilmentRepository;
    private MarketOrderRepository orderRepository;
    private SettlementService settlementService;
    private AuditService auditService;
    private ParcelStockReturner stockReturner;
    private ApplicationEventPublisher eventPublisher;
    private SimpleMeterRegistry registry;
    private FulfilmentService service;

    @BeforeEach
    void setUp() {
        fulfilmentRepository = mock(OrderFulfilmentRepository.class);
        orderRepository = mock(MarketOrderRepository.class);
        MarketOrderItemRepository itemRepository = mock(MarketOrderItemRepository.class);
        settlementService = mock(SettlementService.class);
        auditService = mock(AuditService.class);
        stockReturner = mock(ParcelStockReturner.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        registry = new SimpleMeterRegistry();
        service = new FulfilmentService(fulfilmentRepository, orderRepository, itemRepository,
                mock(MarketOrderEventRepository.class), settlementService, auditService,
                new MarketplaceMetrics(registry), mock(CollectCodeAttempts.class),
                mock(CollectCodeNotifier.class), stockReturner, eventPublisher,
                mock(MarketOrderDeliveryFeeRepository.class),
                TestParcelViews.over(orderRepository, itemRepository, settlementService));
        when(fulfilmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        order(DeliveryMethod.DELIVERY);
    }

    private void order(DeliveryMethod method) {
        Instant now = Instant.now();
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(MarketOrder.builder()
                .id(ORDER_ID).orderRef("MKT-4F9A1C22B7D3").buyerUuid(BUYER_UUID)
                .buyerMsisdn("+263771234567").status(OrderStatus.PAID)
                .subtotalCents(3100).deliveryFeeCents(800).totalCents(3900).currency("USD")
                .deliveryMethod(method)
                .expiresAt(now).paidAt(now).createdAt(now).updatedAt(now).build()));
    }

    private OrderFulfilment parcel(FulfilmentStatus status) {
        Instant now = Instant.now();
        OrderFulfilment p = OrderFulfilment.builder()
                .id(UUID.randomUUID()).orderId(ORDER_ID).merchantId(MERCHANT).status(status)
                .trackingCode("TRK-7F3K9Q2M4X").deliveryFeeCents(800)
                .createdAt(now).updatedAt(now).version(0L).build();
        when(fulfilmentRepository.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    private MerchantSettlement settlement(OrderFulfilment parcel, SettlementStatus status) {
        Instant now = Instant.now();
        MerchantSettlement s = MerchantSettlement.builder()
                .id(UUID.randomUUID()).orderId(ORDER_ID).fulfilmentId(parcel.getId())
                .merchantId(MERCHANT).status(status)
                .grossCents(3900).commissionCents(0).netCents(3900).currency("USD")
                .createdAt(now).updatedAt(now).version(0L).build();
        when(settlementService.forParcel(parcel.getId())).thenReturn(s);
        return s;
    }

    @Test
    @DisplayName("Cancelling ends the parcel as the BUYER's, returns the stock and queues the refund")
    void cancellingDoesAllThreeThings() {
        OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);
        MerchantSettlement settlement = settlement(parcel, SettlementStatus.HELD);

        service.cancelByBuyer(BUYER, parcel.getId(), "  Ordered the wrong size ");

        assertThat(parcel.getStatus()).isEqualTo(FulfilmentStatus.UNFULFILLED);
        assertThat(parcel.getUnfulfilledBy()).isEqualTo(UnfulfilledBy.BUYER);
        assertThat(parcel.getUnfulfilledReason()).isEqualTo("Ordered the wrong size");
        assertThat(parcel.getUnfulfilledAt()).isNotNull();
        assertThat(ParcelCloseMethod.of(parcel, DeliveryMethod.DELIVERY))
                .isEqualTo(ParcelCloseMethod.BUYER_CANCELLED);
        verify(stockReturner).returnOnce(parcel);
        verify(settlementService).markRefundDueCancelledByBuyer(settlement);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(AuditEventType.FULFILMENT_UNFULFILLED),
                eq(BUYER_UUID.toString()), eq(parcel.getId().toString()), metadata.capture());
        assertThat(metadata.getValue()).containsEntry("cancelledByBuyer", true)
                .containsEntry("refundDueCents", 3900L)
                // The buyer's free text never enters the audit trail.
                .doesNotContainValue("Ordered the wrong size");

        // The SELLER is told (after commit); the buyer's own SMS path is not
        // used — they did this on the screen in front of them.
        verify(eventPublisher).publishEvent(new ParcelCancelledByBuyer(MERCHANT,
                "MKT-4F9A1C22B7D3", parcel.getId(), "Ordered the wrong size"));
        verify(eventPublisher, never()).publishEvent(any(ParcelUnfulfilled.class));
    }

    @Test
    @DisplayName("A reason is optional")
    void noReasonIsFine() {
        OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);
        settlement(parcel, SettlementStatus.HELD);

        service.cancelByBuyer(BUYER, parcel.getId(), null);

        assertThat(parcel.getStatus()).isEqualTo(FulfilmentStatus.UNFULFILLED);
        assertThat(parcel.getUnfulfilledReason()).isNull();
        assertThat(parcel.getUnfulfilledBy()).isEqualTo(UnfulfilledBy.BUYER);
    }

    @Test
    @DisplayName("Another buyer's parcel is the same 404 as a missing one")
    void ownerMasked() {
        OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);
        settlement(parcel, SettlementStatus.HELD);

        assertThatThrownBy(() -> service.cancelByBuyer(SOMEONE_ELSE, parcel.getId(), null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("fulfilment_not_found"));
        assertThat(parcel.getStatus()).isEqualTo(FulfilmentStatus.PREPARING);
        verifyNoInteractions(stockReturner, auditService, eventPublisher);
    }

    @Test
    @DisplayName("Once sent — on the road or set aside at the counter — it is too late")
    void tooLateOnceDispatched() {
        for (DeliveryMethod method : DeliveryMethod.values()) {
            order(method);
            OrderFulfilment parcel = parcel(FulfilmentStatus.DISPATCHED);
            settlement(parcel, SettlementStatus.HELD);

            assertThatThrownBy(() -> service.cancelByBuyer(BUYER, parcel.getId(), null))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.code()).isEqualTo("parcel_not_cancellable");
                        assertThat(ex.getMessage()).contains("already sent");
                    });
            assertThat(parcel.getStatus()).isEqualTo(FulfilmentStatus.DISPATCHED);
        }
        verify(settlementService, never()).markRefundDueCancelledByBuyer(any());
        verifyNoInteractions(stockReturner, eventPublisher);
    }

    @Test
    @DisplayName("Closed parcels cannot be cancelled")
    void closedParcelsRefused() {
        for (FulfilmentStatus closed : new FulfilmentStatus[] {
                FulfilmentStatus.DELIVERED, FulfilmentStatus.UNFULFILLED}) {
            OrderFulfilment parcel = parcel(closed);

            assertThatThrownBy(() -> service.cancelByBuyer(BUYER, parcel.getId(), null))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.code()).isEqualTo("parcel_not_cancellable"));
        }
        verifyNoInteractions(stockReturner, eventPublisher);
    }

    @Test
    @DisplayName("A disputed parcel is the operator's — refused before anything moves")
    void disputedIsTheOperators() {
        OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);
        settlement(parcel, SettlementStatus.DISPUTED);

        assertThatThrownBy(() -> service.cancelByBuyer(BUYER, parcel.getId(), null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("parcel_disputed"));
        assertThat(parcel.getStatus()).isEqualTo(FulfilmentStatus.PREPARING);
        verifyNoInteractions(stockReturner, eventPublisher);
    }

    @Test
    @DisplayName("Money no longer HELD (or never recorded) cannot be promised back")
    void moneyNotHeldIsRefused() {
        for (SettlementStatus status : new SettlementStatus[] {
                SettlementStatus.RELEASABLE, SettlementStatus.PAID_OUT}) {
            OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);
            settlement(parcel, status);

            assertThatThrownBy(() -> service.cancelByBuyer(BUYER, parcel.getId(), null))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.code()).isEqualTo("parcel_not_cancellable"));
            assertThat(parcel.getStatus()).isEqualTo(FulfilmentStatus.PREPARING);
        }
        OrderFulfilment legacy = parcel(FulfilmentStatus.PREPARING);
        when(settlementService.forParcel(legacy.getId())).thenReturn(null);
        assertThatThrownBy(() -> service.cancelByBuyer(BUYER, legacy.getId(), null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("parcel_not_cancellable"));
        verifyNoInteractions(stockReturner, eventPublisher);
    }

    @Test
    @DisplayName("A seller's decline is recorded as the SELLER's")
    void sellerDeclineRecordsSeller() {
        AuthenticatedUser seller = new AuthenticatedUser(UUID.randomUUID().toString(),
                Set.of("MERCHANT_ADMIN"), MERCHANT.toString(), null, null, "ZW");
        OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);
        settlement(parcel, SettlementStatus.HELD);

        service.markUnfulfillable(seller, parcel.getId(),
                new com.innbucks.marketplaceservice.fulfilment.dto.UnfulfillableRequest("Out of stock"));

        assertThat(parcel.getUnfulfilledBy()).isEqualTo(UnfulfilledBy.SELLER);
        assertThat(ParcelCloseMethod.of(parcel, DeliveryMethod.DELIVERY))
                .isEqualTo(ParcelCloseMethod.CANNOT_SUPPLY);
    }
}
