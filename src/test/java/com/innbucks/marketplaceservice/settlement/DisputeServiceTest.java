package com.innbucks.marketplaceservice.settlement;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.DeliveryConfirmer;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.order.OrderStatus;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.settlement.dto.DisputeResponse;
import com.innbucks.marketplaceservice.settlement.dto.ResolveDisputeRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the dispute rules: who may open one, the window it must land in, the
 * one-per-parcel-ever cap, and the operator's two ways of closing it.
 */
class DisputeServiceTest {

    private static final long WINDOW_DAYS = 7;
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID BUYER_UUID = UUID.randomUUID();
    private static final UUID MERCHANT_ID = UUID.randomUUID();
    private static final AuthenticatedUser BUYER = new AuthenticatedUser(
            BUYER_UUID.toString(), Set.of("CUSTOMER"), null, null, null, "ZW");
    private static final AuthenticatedUser OPERATOR = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("SUPER_ADMIN"), null, null, null, "ZW");

    private SettlementDisputeRepository disputeRepository;
    private MerchantSettlementRepository settlementRepository;
    private OrderFulfilmentRepository fulfilmentRepository;
    private MarketOrderRepository orderRepository;
    private SettlementService settlementService;
    private AuditService auditService;
    private ApplicationEventPublisher eventPublisher;
    private DisputeService service;

    @BeforeEach
    void setUp() {
        disputeRepository = mock(SettlementDisputeRepository.class);
        settlementRepository = mock(MerchantSettlementRepository.class);
        fulfilmentRepository = mock(OrderFulfilmentRepository.class);
        orderRepository = mock(MarketOrderRepository.class);
        settlementService = mock(SettlementService.class);
        auditService = mock(AuditService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new DisputeService(disputeRepository, settlementRepository,
                fulfilmentRepository, orderRepository, settlementService, auditService,
                new MarketplaceMetrics(new SimpleMeterRegistry()), eventPublisher, WINDOW_DAYS);
        when(disputeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static MarketOrder order(OrderStatus status) {
        Instant now = Instant.now();
        return MarketOrder.builder()
                .id(ORDER_ID).orderRef("MKT-4F9A1C22B7D3").buyerUuid(BUYER_UUID)
                .buyerMsisdn("+263771234567").status(status)
                .subtotalCents(4798).deliveryFeeCents(0).totalCents(4798).currency("USD")
                .deliveryMethod(DeliveryMethod.COLLECTION)
                .expiresAt(now).paidAt(now).createdAt(now).updatedAt(now).build();
    }

    private static OrderFulfilment parcel(Instant deliveredAt) {
        Instant now = Instant.now();
        return OrderFulfilment.builder()
                .id(UUID.randomUUID()).orderId(ORDER_ID).merchantId(MERCHANT_ID)
                .status(deliveredAt == null ? FulfilmentStatus.PREPARING
                        : FulfilmentStatus.DELIVERED)
                .deliveredBy(deliveredAt == null ? null : DeliveryConfirmer.MERCHANT)
                .deliveredAt(deliveredAt)
                .createdAt(now).updatedAt(now).version(0L).build();
    }

    private static MerchantSettlement settlement(UUID fulfilmentId, SettlementStatus status) {
        Instant now = Instant.now();
        return MerchantSettlement.builder()
                .id(UUID.randomUUID()).orderId(ORDER_ID).fulfilmentId(fulfilmentId)
                .merchantId(MERCHANT_ID).status(status)
                .grossCents(4798).commissionCents(0).netCents(4798).currency("USD")
                .createdAt(now).updatedAt(now).version(0L).build();
    }

    private static SettlementDispute dispute(MerchantSettlement s, DisputeStatus status) {
        return SettlementDispute.builder()
                .id(UUID.randomUUID()).settlementId(s.getId()).fulfilmentId(s.getFulfilmentId())
                .orderId(ORDER_ID).merchantId(MERCHANT_ID).buyerUuid(BUYER_UUID)
                .reason(DisputeReason.NOT_RECEIVED).status(status)
                .createdAt(Instant.now()).build();
    }

    /** Wires the happy path's four lookups in one line per test. */
    private OrderFulfilment wire(OrderStatus orderStatus, Instant deliveredAt,
                                 SettlementStatus settlementStatus) {
        when(orderRepository.findByIdAndBuyerUuid(ORDER_ID, BUYER_UUID))
                .thenReturn(Optional.of(order(orderStatus)));
        OrderFulfilment parcel = parcel(deliveredAt);
        when(fulfilmentRepository.findById(parcel.getId())).thenReturn(Optional.of(parcel));
        if (settlementStatus != null) {
            when(settlementRepository.findByFulfilmentId(parcel.getId()))
                    .thenReturn(Optional.of(settlement(parcel.getId(), settlementStatus)));
        } else {
            when(settlementRepository.findByFulfilmentId(parcel.getId()))
                    .thenReturn(Optional.empty());
        }
        return parcel;
    }

    private ApiException openExpectingRefusal(OrderFulfilment parcel) {
        try {
            service.open(BUYER, ORDER_ID, parcel.getId(), DisputeReason.NOT_RECEIVED, null);
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected the open to be refused");
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A delivered parcel inside the window: dispute saved, settlement frozen, reason-only audit")
    void opensAndFreezesInsideTheWindow() {
        OrderFulfilment parcel = wire(OrderStatus.PAID,
                Instant.now().minus(Duration.ofDays(2)), SettlementStatus.HELD);

        DisputeResponse response = service.open(BUYER, ORDER_ID, parcel.getId(),
                DisputeReason.DAMAGED, "Screen <b>cracked</b> on arrival");

        assertThat(response.status()).isEqualTo(DisputeStatus.OPEN);
        assertThat(response.reason()).isEqualTo(DisputeReason.DAMAGED);
        // jsoup strips the markup; the words survive.
        assertThat(response.detail()).isEqualTo("Screen cracked on arrival");
        verify(settlementService).freeze(any(), eq(DisputeReason.DAMAGED));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditService).record(eq(AuditEventType.SETTLEMENT_DISPUTED), eq(BUYER.uuid()),
                anyString(), metadata.capture());
        // The bounded reason is evidence; the buyer's free text never enters
        // the tamper-evident trail (V7 report stance).
        assertThat(metadata.getValue()).containsEntry("reason", "DAMAGED");
        assertThat(metadata.getValue().toString()).doesNotContain("cracked");
    }

    @Test
    @DisplayName("An UNDELIVERED parcel is disputable however old — 'it never arrived' IS the refund path")
    void undeliveredParcelIsAlwaysDisputable() {
        OrderFulfilment parcel = wire(OrderStatus.PAID, null, SettlementStatus.HELD);

        DisputeResponse response = service.open(BUYER, ORDER_ID, parcel.getId(),
                DisputeReason.NOT_RECEIVED, null);

        assertThat(response.status()).isEqualTo(DisputeStatus.OPEN);
        verify(settlementService).freeze(any(), eq(DisputeReason.NOT_RECEIVED));
    }

    @Test
    @DisplayName("RELEASABLE is still arguable — confirming receipt does not sign away the dispute")
    void releasableIsStillDisputable() {
        OrderFulfilment parcel = wire(OrderStatus.PAID,
                Instant.now().minus(Duration.ofDays(1)), SettlementStatus.RELEASABLE);

        assertThat(service.open(BUYER, ORDER_ID, parcel.getId(),
                DisputeReason.NOT_AS_DESCRIBED, null).status())
                .isEqualTo(DisputeStatus.OPEN);
    }

    @Test
    @DisplayName("Someone else's order is the same 404 as a nonexistent one")
    void ownerMasking() {
        when(orderRepository.findByIdAndBuyerUuid(ORDER_ID, BUYER_UUID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open(BUYER, ORDER_ID, UUID.randomUUID(),
                DisputeReason.NOT_RECEIVED, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("order_not_found");
    }

    @Test
    @DisplayName("A parcel belonging to a DIFFERENT order 404s, not 500s")
    void parcelMustBelongToTheOrder() {
        when(orderRepository.findByIdAndBuyerUuid(ORDER_ID, BUYER_UUID))
                .thenReturn(Optional.of(order(OrderStatus.PAID)));
        OrderFulfilment foreign = OrderFulfilment.builder()
                .id(UUID.randomUUID()).orderId(UUID.randomUUID()).merchantId(MERCHANT_ID)
                .status(FulfilmentStatus.PREPARING)
                .createdAt(Instant.now()).updatedAt(Instant.now()).version(0L).build();
        when(fulfilmentRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.open(BUYER, ORDER_ID, foreign.getId(),
                DisputeReason.NOT_RECEIVED, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("fulfilment_not_found");
    }

    @Test
    @DisplayName("Refusal codes: unpaid order, missing settlement, window closed, already decided, already raised")
    void everyGuardHasItsOwnCode() {
        assertThat(openExpectingRefusal(wire(OrderStatus.PENDING_PAYMENT, null,
                SettlementStatus.HELD)).code()).isEqualTo("order_not_paid");
        assertThat(openExpectingRefusal(wire(OrderStatus.PAID, null, null)).code())
                .isEqualTo("settlement_missing");
        assertThat(openExpectingRefusal(wire(OrderStatus.PAID,
                Instant.now().minus(Duration.ofDays(WINDOW_DAYS + 1)), SettlementStatus.HELD))
                .code()).isEqualTo("dispute_window_closed");
        assertThat(openExpectingRefusal(wire(OrderStatus.PAID, null,
                SettlementStatus.PAID_OUT)).code()).isEqualTo("settlement_already_paid_out");
        assertThat(openExpectingRefusal(wire(OrderStatus.PAID, null,
                SettlementStatus.REFUNDED)).code()).isEqualTo("settlement_already_refunded");
        assertThat(openExpectingRefusal(wire(OrderStatus.PAID, null,
                SettlementStatus.DISPUTED)).code()).isEqualTo("dispute_already_raised");
        // No refused open froze anything or wrote anything.
        verify(settlementService, never()).freeze(any(), any());
        verify(disputeRepository, never()).save(any());
    }

    @Test
    @DisplayName("One dispute per parcel EVER — a resolved one still blocks a second")
    void oneDisputePerParcelEver() {
        OrderFulfilment parcel = wire(OrderStatus.PAID, null, SettlementStatus.HELD);
        MerchantSettlement s = settlement(parcel.getId(), SettlementStatus.HELD);
        when(disputeRepository.findByFulfilmentId(parcel.getId()))
                .thenReturn(Optional.of(dispute(s, DisputeStatus.RELEASED)));

        assertThat(openExpectingRefusal(parcel).code()).isEqualTo("dispute_already_raised");
        verify(settlementService, never()).freeze(any(), any());
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RELEASE: money back to RELEASABLE, dispute RELEASED, buyer told after commit")
    void releaseResolution() {
        MerchantSettlement s = settlement(UUID.randomUUID(), SettlementStatus.DISPUTED);
        SettlementDispute d = dispute(s, DisputeStatus.OPEN);
        when(disputeRepository.findById(d.getId())).thenReturn(Optional.of(d));
        when(settlementRepository.findById(s.getId())).thenReturn(Optional.of(s));
        when(orderRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(order(OrderStatus.PAID)));

        DisputeResponse response = service.resolve(OPERATOR, d.getId(),
                new ResolveDisputeRequest(ResolveDisputeRequest.Action.RELEASE,
                        "Courier photo shows delivery", null));

        verify(settlementService).releaseByOperator(s);
        assertThat(response.status()).isEqualTo(DisputeStatus.RELEASED);
        assertThat(response.resolutionNote()).isEqualTo("Courier photo shows delivery");
        assertThat(d.getResolvedBy()).isEqualTo(UUID.fromString(OPERATOR.uuid()));
        assertThat(d.getResolvedAt()).isNotNull();

        ArgumentCaptor<DisputeResolved> event = ArgumentCaptor.forClass(DisputeResolved.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().outcome()).isEqualTo(DisputeStatus.RELEASED);
        assertThat(event.getValue().buyerUuid()).isEqualTo(BUYER_UUID);
        assertThat(event.getValue().orderRef()).isEqualTo("MKT-4F9A1C22B7D3");
    }

    @Test
    @DisplayName("REFUND: recorded with the operator's reference, dispute REFUNDED, audited")
    void refundResolution() {
        MerchantSettlement s = settlement(UUID.randomUUID(), SettlementStatus.DISPUTED);
        SettlementDispute d = dispute(s, DisputeStatus.OPEN);
        when(disputeRepository.findById(d.getId())).thenReturn(Optional.of(d));
        when(settlementRepository.findById(s.getId())).thenReturn(Optional.of(s));
        when(orderRepository.findById(ORDER_ID))
                .thenReturn(Optional.of(order(OrderStatus.PAID)));

        DisputeResponse response = service.resolve(OPERATOR, d.getId(),
                new ResolveDisputeRequest(ResolveDisputeRequest.Action.REFUND,
                        null, "RFND-2026-09-30-07"));

        verify(settlementService).recordRefund(s, "RFND-2026-09-30-07");
        assertThat(response.status()).isEqualTo(DisputeStatus.REFUNDED);
        verify(auditService).record(eq(AuditEventType.SETTLEMENT_DISPUTE_RESOLVED),
                eq(OPERATOR.uuid()), eq(d.getId().toString()), anyMap());

        ArgumentCaptor<DisputeResolved> event = ArgumentCaptor.forClass(DisputeResolved.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().outcome()).isEqualTo(DisputeStatus.REFUNDED);
        assertThat(event.getValue().netCents()).isEqualTo(4798);
    }

    @Test
    @DisplayName("A dispute is resolved exactly once — the second decision is refused, nothing re-runs")
    void resolvedIsTerminal() {
        MerchantSettlement s = settlement(UUID.randomUUID(), SettlementStatus.REFUNDED);
        SettlementDispute d = dispute(s, DisputeStatus.REFUNDED);
        when(disputeRepository.findById(d.getId())).thenReturn(Optional.of(d));

        assertThatThrownBy(() -> service.resolve(OPERATOR, d.getId(),
                new ResolveDisputeRequest(ResolveDisputeRequest.Action.RELEASE, null, null)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("dispute_not_open");
        verify(settlementService, never()).releaseByOperator(any());
        verify(settlementService, never()).recordRefund(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("The queue names each row's frozen money, from ONE batched settlement read")
    void queueCarriesTheFrozenMoney() {
        MerchantSettlement s = settlement(UUID.randomUUID(), SettlementStatus.DISPUTED);
        SettlementDispute withMoney = dispute(s, DisputeStatus.OPEN);
        SettlementDispute orphaned = dispute(
                settlement(UUID.randomUUID(), SettlementStatus.DISPUTED), DisputeStatus.OPEN);
        when(disputeRepository.findByStatusOrderByCreatedAtAsc(eq(DisputeStatus.OPEN), any()))
                .thenReturn(new PageImpl<>(List.of(withMoney, orphaned)));
        when(settlementRepository.findAllById(any())).thenReturn(List.of(s));

        Page<DisputeResponse> page = service.queue(DisputeStatus.OPEN, 0, 20);

        assertThat(page.getContent().get(0).netCents()).isEqualTo(4798L);
        assertThat(page.getContent().get(0).currency()).isEqualTo("USD");
        // A settlement the batch could not find (never expected) costs that
        // row its amount, never the whole page.
        assertThat(page.getContent().get(1).netCents()).isNull();
        assertThat(page.getContent().get(1).currency()).isNull();
        // The assembler discipline: one grouped read, never one per row.
        verify(settlementRepository, times(1)).findAllById(any());
    }
}
