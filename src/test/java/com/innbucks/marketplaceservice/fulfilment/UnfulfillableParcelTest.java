package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.collect.CollectCodeAttempts;
import com.innbucks.marketplaceservice.fulfilment.dto.UnfulfillableRequest;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.notify.CollectCodeNotifier;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderEventRepository;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.order.OrderStatus;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.settlement.MerchantSettlement;
import com.innbucks.marketplaceservice.settlement.SettlementService;
import com.innbucks.marketplaceservice.settlement.SettlementStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the seller's way out of a parcel they cannot supply (V12) — and, just
 * as importantly, what happens to the buyer's money when they take it.
 */
class UnfulfillableParcelTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID MERCHANT_A = UUID.randomUUID();
    private static final AuthenticatedUser SELLER = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("MERCHANT_ADMIN"),
            MERCHANT_A.toString(), null, null, "ZW");

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
                mock(CollectCodeNotifier.class), stockReturner, eventPublisher);
        when(fulfilmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(
                MarketOrderItem.builder().id(UUID.randomUUID()).orderId(ORDER_ID)
                        .listingId(UUID.randomUUID()).merchantId(MERCHANT_A)
                        .titleSnapshot("Solar Lantern 20W").unitPriceCents(1550)
                        .quantity(2).lineTotalCents(3100).build()));
        Instant now = Instant.now();
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(MarketOrder.builder()
                .id(ORDER_ID).orderRef("MKT-4F9A1C22B7D3").buyerUuid(UUID.randomUUID())
                .buyerMsisdn("+263771234567").status(OrderStatus.PAID)
                .subtotalCents(3100).deliveryFeeCents(0).totalCents(3100).currency("USD")
                .deliveryMethod(DeliveryMethod.COLLECTION)
                .expiresAt(now).paidAt(now).createdAt(now).updatedAt(now).build()));
    }

    private OrderFulfilment parcel(FulfilmentStatus status) {
        Instant now = Instant.now();
        OrderFulfilment p = OrderFulfilment.builder()
                .id(UUID.randomUUID()).orderId(ORDER_ID).merchantId(MERCHANT_A).status(status)
                .createdAt(now).updatedAt(now).version(0L).build();
        when(fulfilmentRepository.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    private MerchantSettlement settlement(SettlementStatus status) {
        Instant now = Instant.now();
        return MerchantSettlement.builder()
                .id(UUID.randomUUID()).orderId(ORDER_ID).fulfilmentId(UUID.randomUUID())
                .merchantId(MERCHANT_A).status(status)
                .grossCents(3100).commissionCents(0).netCents(3100).currency("USD")
                .createdAt(now).updatedAt(now).version(0L).build();
    }

    private UnfulfillableRequest reason(String text) {
        return new UnfulfillableRequest(text);
    }

    @Test
    @DisplayName("Declining ends the parcel, returns the stock and turns the money around — together")
    void decliningDoesAllThreeThings() {
        OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);
        MerchantSettlement settlement = settlement(SettlementStatus.HELD);
        when(settlementService.forParcel(parcel.getId())).thenReturn(settlement);

        service.markUnfulfillable(SELLER, parcel.getId(), reason("Out of stock"));

        assertThat(parcel.getStatus()).isEqualTo(FulfilmentStatus.UNFULFILLED);
        assertThat(parcel.getUnfulfilledReason()).isEqualTo("Out of stock");
        assertThat(parcel.getUnfulfilledAt()).isNotNull();
        verify(stockReturner).returnOnce(parcel);
        verify(settlementService).markRefundDue(settlement, "Out of stock");
        verify(auditService).record(eq(AuditEventType.FULFILMENT_UNFULFILLED), anyString(),
                eq(parcel.getId().toString()), anyMap());
    }

    @Test
    @DisplayName("The buyer is told, with the amount actually queued for refund")
    void theBuyerIsTold() {
        OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);
        when(settlementService.forParcel(parcel.getId()))
                .thenReturn(settlement(SettlementStatus.HELD));

        service.markUnfulfillable(SELLER, parcel.getId(), reason("Out of stock"));

        ArgumentCaptor<ParcelUnfulfilled> event = ArgumentCaptor.forClass(ParcelUnfulfilled.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().orderRef()).isEqualTo("MKT-4F9A1C22B7D3");
        assertThat(event.getValue().buyerMsisdn()).isEqualTo("+263771234567");
        assertThat(event.getValue().sellerReason()).isEqualTo("Out of stock");
        assertThat(event.getValue().refundDueCents()).isEqualTo(3100);
        assertThat(event.getValue().refundQueued()).isTrue();
    }

    @Test
    @DisplayName("A parcel already disputed still closes, but its money is left where it is")
    void moneyAlreadyWithAnOperatorIsNotTouched() {
        OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);
        when(settlementService.forParcel(parcel.getId()))
                .thenReturn(settlement(SettlementStatus.DISPUTED));

        service.markUnfulfillable(SELLER, parcel.getId(), reason("Out of stock"));

        // Refusing the decline would leave the parcel open, which is the exact
        // state this exists to end; moving the money would take a decision away
        // from the operator already holding it.
        assertThat(parcel.getStatus()).isEqualTo(FulfilmentStatus.UNFULFILLED);
        verify(settlementService, never()).markRefundDue(any(), anyString());
        ArgumentCaptor<ParcelUnfulfilled> event = ArgumentCaptor.forClass(ParcelUnfulfilled.class);
        verify(eventPublisher).publishEvent(event.capture());
        // ...and the copy must not promise a refund that was not queued.
        assertThat(event.getValue().refundQueued()).isFalse();
    }

    @Test
    @DisplayName("A parcel with no settlement row closes and invents no money")
    void missingSettlementIsNeverInvented() {
        OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);
        when(settlementService.forParcel(parcel.getId())).thenReturn(null);

        service.markUnfulfillable(SELLER, parcel.getId(), reason("Out of stock"));

        assertThat(parcel.getStatus()).isEqualTo(FulfilmentStatus.UNFULFILLED);
        verify(settlementService, never()).markRefundDue(any(), anyString());
    }

    @Test
    @DisplayName("A dispatched parcel cannot be declined — nothing is touched")
    void dispatchedCannotBeDeclined() {
        OrderFulfilment parcel = parcel(FulfilmentStatus.DISPATCHED);

        assertThatThrownBy(() -> service.markUnfulfillable(SELLER, parcel.getId(),
                reason("Out of stock")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("illegal_fulfilment_state");

        assertThat(parcel.getStatus()).isEqualTo(FulfilmentStatus.DISPATCHED);
        assertThat(parcel.getUnfulfilledAt()).isNull();
        verify(stockReturner, never()).returnOnce(any());
        verify(settlementService, never()).markRefundDue(any(), anyString());
        verify(eventPublisher, never()).publishEvent(any(ParcelUnfulfilled.class));
    }

    @Test
    @DisplayName("A reason that sanitizes down to nothing is refused before anything moves")
    void aReasonThatIsOnlyMarkupIsRefused() {
        OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);

        assertThatThrownBy(() -> service.markUnfulfillable(SELLER, parcel.getId(),
                reason("<script>x()</script>")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("unfulfilled_reason_required");

        assertThat(parcel.getStatus()).isEqualTo(FulfilmentStatus.PREPARING);
        verify(stockReturner, never()).returnOnce(any());
    }

    @Test
    @DisplayName("Another seller cannot decline a parcel that is not theirs")
    void decliningIsSellerScoped() {
        OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);
        AuthenticatedUser otherSeller = new AuthenticatedUser(UUID.randomUUID().toString(),
                Set.of("MERCHANT_ADMIN"), UUID.randomUUID().toString(), null, null, "ZW");

        assertThatThrownBy(() -> service.markUnfulfillable(otherSeller, parcel.getId(),
                reason("Out of stock")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("fulfilment_not_found");
    }

    // ------------------------------------------------------------------
    // The order-level summary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Roll-up ignores a declined parcel and summarises what is still owed")
    void rollUpExcludesUnfulfilled() {
        OrderFulfilment declined = parcel(FulfilmentStatus.UNFULFILLED);
        OrderFulfilment onItsWay = parcel(FulfilmentStatus.DISPATCHED);

        // Not UNFULFILLED (half of it is on its way) and not DELIVERED (nothing
        // has arrived): the summary describes the parcels still in play.
        assertThat(FulfilmentService.rollUp(List.of(declined, onItsWay)))
                .isEqualTo(FulfilmentStatus.DISPATCHED);
        assertThat(FulfilmentService.rollUp(List.of(declined, parcel(FulfilmentStatus.DELIVERED))))
                .isEqualTo(FulfilmentStatus.DELIVERED);
    }

    @Test
    @DisplayName("An order whose every parcel was declined rolls up to UNFULFILLED, not null")
    void allDeclinedRollsUpToUnfulfilled() {
        assertThat(FulfilmentService.rollUp(List.of(parcel(FulfilmentStatus.UNFULFILLED),
                parcel(FulfilmentStatus.UNFULFILLED))))
                .isEqualTo(FulfilmentStatus.UNFULFILLED);
        // Null keeps its own meaning: nothing was ever opened, because nothing
        // was ever paid for.
        assertThat(FulfilmentService.rollUp(List.of())).isNull();
    }
}
