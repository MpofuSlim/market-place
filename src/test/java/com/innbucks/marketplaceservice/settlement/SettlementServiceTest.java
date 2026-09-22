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
import com.innbucks.marketplaceservice.order.MarketOrderEvent;
import com.innbucks.marketplaceservice.order.MarketOrderEventRepository;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.OrderStatus;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the escrow engine's money rules: what opens, what releases when, what
 * a dispute freezes, and what one payout run covers.
 */
class SettlementServiceTest {

    private static final long GRACE_HOURS = 48;
    private static final long STALE_AFTER_DAYS = 14;
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID MERCHANT_A = UUID.randomUUID();
    private static final UUID MERCHANT_B = UUID.randomUUID();
    private static final AuthenticatedUser OPERATOR = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("SUPER_ADMIN"), null, null, null, "ZW");

    private MerchantSettlementRepository settlementRepository;
    private OrderFulfilmentRepository fulfilmentRepository;
    private MarketOrderItemRepository itemRepository;
    private MarketOrderEventRepository eventRepository;
    private AuditService auditService;
    private SimpleMeterRegistry registry;
    private SettlementService service;

    @BeforeEach
    void setUp() {
        settlementRepository = mock(MerchantSettlementRepository.class);
        fulfilmentRepository = mock(OrderFulfilmentRepository.class);
        itemRepository = mock(MarketOrderItemRepository.class);
        eventRepository = mock(MarketOrderEventRepository.class);
        auditService = mock(AuditService.class);
        registry = new SimpleMeterRegistry();
        service = new SettlementService(settlementRepository, fulfilmentRepository,
                itemRepository, eventRepository, auditService,
                new MarketplaceMetrics(registry), GRACE_HOURS, STALE_AFTER_DAYS, 0.0);
        when(settlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static MarketOrder order() {
        Instant now = Instant.now();
        return MarketOrder.builder()
                .id(ORDER_ID).orderRef("MKT-4F9A1C22B7D3").buyerUuid(UUID.randomUUID())
                .buyerMsisdn("+263771234567").status(OrderStatus.PAID)
                .subtotalCents(7397).deliveryFeeCents(0).totalCents(7397).currency("USD")
                .deliveryMethod(DeliveryMethod.COLLECTION)
                .expiresAt(now).paidAt(now).createdAt(now).updatedAt(now).build();
    }

    private static MarketOrderItem item(UUID merchantId, long lineTotal) {
        return MarketOrderItem.builder()
                .id(UUID.randomUUID()).orderId(ORDER_ID).listingId(UUID.randomUUID())
                .merchantId(merchantId).titleSnapshot("x").unitPriceCents(lineTotal)
                .quantity(1).lineTotalCents(lineTotal).build();
    }

    private static OrderFulfilment parcel(UUID merchantId, FulfilmentStatus status,
                                          DeliveryConfirmer deliveredBy) {
        Instant now = Instant.now();
        return OrderFulfilment.builder()
                .id(UUID.randomUUID()).orderId(ORDER_ID).merchantId(merchantId)
                .status(status).deliveredBy(deliveredBy)
                .deliveredAt(status == FulfilmentStatus.DELIVERED ? now : null)
                .createdAt(now).updatedAt(now).version(0L).build();
    }

    private static MerchantSettlement settlement(SettlementStatus status) {
        Instant now = Instant.now();
        return MerchantSettlement.builder()
                .id(UUID.randomUUID()).orderId(ORDER_ID).fulfilmentId(UUID.randomUUID())
                .merchantId(MERCHANT_A).status(status)
                .grossCents(4798).commissionCents(0).netCents(4798).currency("USD")
                .createdAt(now).updatedAt(now).version(0L).build();
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    @Test
    @DisplayName("One HELD settlement per parcel, gross = that seller's line totals")
    void opensPerParcelWithThatSellersMoney() {
        when(itemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(
                item(MERCHANT_A, 3100), item(MERCHANT_B, 2599), item(MERCHANT_A, 1698)));
        OrderFulfilment parcelA = parcel(MERCHANT_A, FulfilmentStatus.PREPARING, null);
        OrderFulfilment parcelB = parcel(MERCHANT_B, FulfilmentStatus.PREPARING, null);
        when(fulfilmentRepository.findByOrderIdOrderByCreatedAtAsc(ORDER_ID))
                .thenReturn(List.of(parcelA, parcelB));
        when(settlementRepository.openIfAbsent(any(), any(), any(), any(), anyLong(), anyLong(),
                anyLong(), anyString(), any())).thenReturn(1);

        service.openForOrder(order());

        verify(settlementRepository).openIfAbsent(any(), eq(ORDER_ID), eq(parcelA.getId()),
                eq(MERCHANT_A), eq(4798L), eq(0L), eq(4798L), eq("USD"), any());
        verify(settlementRepository).openIfAbsent(any(), eq(ORDER_ID), eq(parcelB.getId()),
                eq(MERCHANT_B), eq(2599L), eq(0L), eq(2599L), eq("USD"), any());
    }

    @Test
    @DisplayName("Commission is withheld per configured percent, net = gross - commission")
    void commissionComesOffTheTop() {
        service = new SettlementService(settlementRepository, fulfilmentRepository,
                itemRepository, eventRepository, auditService,
                new MarketplaceMetrics(registry), GRACE_HOURS, STALE_AFTER_DAYS, 5.0);
        when(itemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(item(MERCHANT_A, 1000)));
        when(fulfilmentRepository.findByOrderIdOrderByCreatedAtAsc(ORDER_ID))
                .thenReturn(List.of(parcel(MERCHANT_A, FulfilmentStatus.PREPARING, null)));
        when(settlementRepository.openIfAbsent(any(), any(), any(), any(), anyLong(), anyLong(),
                anyLong(), anyString(), any())).thenReturn(1);

        service.openForOrder(order());

        verify(settlementRepository).openIfAbsent(any(), any(), any(), any(),
                eq(1000L), eq(50L), eq(950L), anyString(), any());
    }

    @Test
    @DisplayName("A replayed confirm opens nothing twice and journals nothing")
    void openingIsIdempotent() {
        when(itemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(item(MERCHANT_A, 1000)));
        when(fulfilmentRepository.findByOrderIdOrderByCreatedAtAsc(ORDER_ID))
                .thenReturn(List.of(parcel(MERCHANT_A, FulfilmentStatus.PREPARING, null)));
        when(settlementRepository.openIfAbsent(any(), any(), any(), any(), anyLong(), anyLong(),
                anyLong(), anyString(), any())).thenReturn(0);

        service.openForOrder(order());

        verify(eventRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // Release on delivery
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The buyer's own confirmation releases IMMEDIATELY — their word waits for nothing")
    void buyerConfirmationReleasesNow() {
        OrderFulfilment p = parcel(MERCHANT_A, FulfilmentStatus.DELIVERED, DeliveryConfirmer.BUYER);
        MerchantSettlement s = settlement(SettlementStatus.HELD);
        when(settlementRepository.findByFulfilmentId(p.getId())).thenReturn(Optional.of(s));

        service.onParcelDelivered(p);

        assertThat(s.getStatus()).isEqualTo(SettlementStatus.RELEASABLE);
        assertThat(s.getReleasedAt()).isNotNull();
        assertThat(s.getReleasableAt()).isNull();
    }

    @Test
    @DisplayName("A seller's self-close only STARTS the grace clock — weaker evidence buys a wait")
    void sellerCloseStartsTheGraceClock() {
        OrderFulfilment p = parcel(MERCHANT_A, FulfilmentStatus.DELIVERED, DeliveryConfirmer.MERCHANT);
        MerchantSettlement s = settlement(SettlementStatus.HELD);
        when(settlementRepository.findByFulfilmentId(p.getId())).thenReturn(Optional.of(s));

        Instant before = Instant.now();
        service.onParcelDelivered(p);

        assertThat(s.getStatus()).isEqualTo(SettlementStatus.HELD);
        assertThat(s.getReleasableAt())
                .isAfterOrEqualTo(before.plusSeconds(GRACE_HOURS * 3600 - 5))
                .isBeforeOrEqualTo(Instant.now().plusSeconds(GRACE_HOURS * 3600 + 5));
        assertThat(s.getReleasedAt()).isNull();
    }

    @Test
    @DisplayName("Delivery does not settle an argument: a DISPUTED settlement is left frozen")
    void deliveryNeverUnfreezesADispute() {
        OrderFulfilment p = parcel(MERCHANT_A, FulfilmentStatus.DELIVERED, DeliveryConfirmer.BUYER);
        MerchantSettlement s = settlement(SettlementStatus.DISPUTED);
        when(settlementRepository.findByFulfilmentId(p.getId())).thenReturn(Optional.of(s));

        service.onParcelDelivered(p);

        assertThat(s.getStatus()).isEqualTo(SettlementStatus.DISPUTED);
        verify(settlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("A parcel with no settlement row logs loudly and invents no money")
    void missingSettlementIsNeverInvented() {
        OrderFulfilment p = parcel(MERCHANT_A, FulfilmentStatus.DELIVERED, DeliveryConfirmer.BUYER);
        when(settlementRepository.findByFulfilmentId(p.getId())).thenReturn(Optional.empty());

        service.onParcelDelivered(p);

        verify(settlementRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // The sweeper's per-row release
    // ------------------------------------------------------------------

    @Test
    @DisplayName("releaseOne promotes a lapsed-grace row and journals it")
    void releaseOnePromotesALapsedRow() {
        MerchantSettlement s = settlement(SettlementStatus.HELD);
        s.setReleasableAt(Instant.now().minusSeconds(60));
        when(settlementRepository.findById(s.getId())).thenReturn(Optional.of(s));

        assertThat(service.releaseOne(s.getId())).isTrue();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.RELEASABLE);
    }

    @Test
    @DisplayName("releaseOne re-checks: a dispute that landed in between wins the race")
    void releaseOneLosesToADispute() {
        MerchantSettlement s = settlement(SettlementStatus.DISPUTED);
        s.setReleasableAt(Instant.now().minusSeconds(60));
        when(settlementRepository.findById(s.getId())).thenReturn(Optional.of(s));

        assertThat(service.releaseOne(s.getId())).isFalse();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.DISPUTED);
    }

    @Test
    @DisplayName("releaseOne never releases early")
    void releaseOneRespectsTheClock() {
        MerchantSettlement s = settlement(SettlementStatus.HELD);
        s.setReleasableAt(Instant.now().plusSeconds(3600));
        when(settlementRepository.findById(s.getId())).thenReturn(Optional.of(s));

        assertThat(service.releaseOne(s.getId())).isFalse();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.HELD);
    }

    // ------------------------------------------------------------------
    // Payout
    // ------------------------------------------------------------------

    @Test
    @DisplayName("One payout run: every RELEASABLE row paid under one reference, ONE audit event")
    void payoutRunCoversEverythingReleasable() {
        MerchantSettlement s1 = settlement(SettlementStatus.RELEASABLE);
        MerchantSettlement s2 = settlement(SettlementStatus.RELEASABLE);
        when(settlementRepository.findByMerchantIdAndStatus(MERCHANT_A, SettlementStatus.RELEASABLE))
                .thenReturn(List.of(s1, s2));

        SettlementService.PayoutOutcome outcome =
                service.payOutReleasable(OPERATOR, MERCHANT_A, "PAYOUT-1");

        assertThat(outcome.parcels()).isEqualTo(2);
        assertThat(outcome.totalNetCents()).isEqualTo(9596);
        assertThat(s1.getStatus()).isEqualTo(SettlementStatus.PAID_OUT);
        assertThat(s1.getPayoutReference()).isEqualTo("PAYOUT-1");
        assertThat(s2.getStatus()).isEqualTo(SettlementStatus.PAID_OUT);
        // One operator decision = one tamper-evident record; the per-parcel
        // trail is each order's journal (2 rows saved there).
        verify(auditService, times(1)).record(eq(AuditEventType.SETTLEMENT_PAID_OUT),
                eq(OPERATOR.uuid()), eq(MERCHANT_A.toString()), anyMap());
        verify(eventRepository, times(2)).save(any(MarketOrderEvent.class));
    }

    @Test
    @DisplayName("An empty payout run is refused — a bank reference over no money describes nothing")
    void emptyPayoutRunIsRefused() {
        when(settlementRepository.findByMerchantIdAndStatus(MERCHANT_A, SettlementStatus.RELEASABLE))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.payOutReleasable(OPERATOR, MERCHANT_A, "PAYOUT-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("nothing_releasable");
        verify(auditService, never()).record(any(), anyString(), anyString(), anyMap());
    }

    // ------------------------------------------------------------------
    // Refund due — the seller cannot supply it (V12)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("markRefundDue turns HELD money around and clears any grace clock")
    void refundDueTurnsHeldMoneyAround() {
        MerchantSettlement s = settlement(SettlementStatus.HELD);
        s.setReleasableAt(Instant.now().plusSeconds(3600));

        service.markRefundDue(s, "out of stock");

        assertThat(s.getStatus()).isEqualTo(SettlementStatus.REFUND_DUE);
        assertThat(s.getRefundDueAt()).isNotNull();
        // The grace clock is cleared, or the release sweeper would still see a
        // due row and try to pay the seller who just said they cannot supply it.
        assertThat(s.getReleasableAt()).isNull();
        // Nothing is recorded as MOVED: no refundedAt, no reference. This
        // service does not send money, and a ledger that stamped one here
        // would be describing a transfer nobody made.
        assertThat(s.getRefundedAt()).isNull();
        assertThat(s.getRefundReference()).isNull();
    }

    @Test
    @DisplayName("The seller's own words ride the journal so an operator can read WHY")
    void refundDueJournalsTheSellersReason() {
        MerchantSettlement s = settlement(SettlementStatus.HELD);

        service.markRefundDue(s, "warehouse fire");

        ArgumentCaptor<MarketOrderEvent> captor = ArgumentCaptor.forClass(MarketOrderEvent.class);
        verify(eventRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getToStatus()).isEqualTo("REFUND_DUE");
        assertThat(captor.getValue().getDetail()).contains("warehouse fire");
    }

    @Test
    @DisplayName("A blank reason still journals a usable line, never a dangling colon")
    void refundDueWithoutAReasonReadsCleanly() {
        MerchantSettlement s = settlement(SettlementStatus.HELD);

        service.markRefundDue(s, "   ");

        ArgumentCaptor<MarketOrderEvent> captor = ArgumentCaptor.forClass(MarketOrderEvent.class);
        verify(eventRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getDetail()).isEqualTo(
                "Refund due - seller could not fulfil the parcel");
    }

    @Test
    @DisplayName("Money already paid out cannot be turned around — refused, and nothing stamped")
    void refundDueRefusesPaidOutMoney() {
        MerchantSettlement s = settlement(SettlementStatus.PAID_OUT);

        assertThatThrownBy(() -> service.markRefundDue(s, "out of stock"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("illegal_settlement_state");
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.PAID_OUT);
        assertThat(s.getRefundDueAt()).isNull();
    }

    @Test
    @DisplayName("Recording the operator's refund closes the row and audits the reference")
    void recordRefundPaymentClosesTheRow() {
        MerchantSettlement s = settlement(SettlementStatus.REFUND_DUE);
        s.setRefundDueAt(Instant.now().minusSeconds(3600));
        when(settlementRepository.findById(s.getId())).thenReturn(Optional.of(s));

        MerchantSettlement out = service.recordRefundPayment(OPERATOR, s.getId(), "RFND-77");

        assertThat(out.getStatus()).isEqualTo(SettlementStatus.REFUNDED);
        assertThat(out.getRefundedAt()).isNotNull();
        assertThat(out.getRefundReference()).isEqualTo("RFND-77");
        verify(auditService, times(1)).record(eq(AuditEventType.SETTLEMENT_REFUNDED),
                eq(OPERATOR.uuid()), eq(s.getId().toString()), anyMap());
    }

    @Test
    @DisplayName("An unknown settlement is a 404, never a silently-created refund")
    void recordRefundPaymentOnAnUnknownRowIs404() {
        UUID missing = UUID.randomUUID();
        when(settlementRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordRefundPayment(OPERATOR, missing, "RFND-77"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("settlement_not_found");
        verify(auditService, never()).record(any(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("A refund cannot be recorded against HELD money — only REFUND_DUE")
    void recordRefundPaymentRefusesHeldMoney() {
        MerchantSettlement s = settlement(SettlementStatus.HELD);
        when(settlementRepository.findById(s.getId())).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.recordRefundPayment(OPERATOR, s.getId(), "RFND-77"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("illegal_settlement_state");
        assertThat(s.getRefundReference()).isNull();
        verify(auditService, never()).record(any(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("A DISPUTED row is refused toward the dispute queue — a direct refund would "
            + "orphan the OPEN dispute")
    void recordRefundPaymentRefusesADisputedRow() {
        // DISPUTED -> REFUNDED is a legal machine edge (the dispute resolve
        // rides it), so without the explicit guard this call would succeed —
        // and the OPEN dispute would then be unresolvable: both resolve
        // actions become illegal transitions from REFUNDED.
        MerchantSettlement s = settlement(SettlementStatus.DISPUTED);
        when(settlementRepository.findById(s.getId())).thenReturn(Optional.of(s));

        assertThatThrownBy(() -> service.recordRefundPayment(OPERATOR, s.getId(), "RFND-77"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("settlement_disputed");
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.DISPUTED);
        assertThat(s.getRefundReference()).isNull();
        verify(auditService, never()).record(any(), anyString(), anyString(), anyMap());
    }

    // ------------------------------------------------------------------
    // Stale escrow
    // ------------------------------------------------------------------

    @Test
    @DisplayName("staleHeld asks for HELD rows older than the configured window, oldest first")
    void staleHeldScansTheConfiguredWindow() {
        when(settlementRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                any(), any(), any())).thenReturn(List.of());

        Instant before = Instant.now();
        service.staleHeld(500);

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(settlementRepository).findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                eq(SettlementStatus.HELD), cutoff.capture(), page.capture());
        // The cutoff is now minus the window: anything created before it has
        // been sitting on the platform's books too long.
        assertThat(cutoff.getValue())
                .isAfterOrEqualTo(before.minus(Duration.ofDays(STALE_AFTER_DAYS)).minusSeconds(5))
                .isBeforeOrEqualTo(Instant.now().minus(Duration.ofDays(STALE_AFTER_DAYS)));
        assertThat(page.getValue().getPageSize()).isEqualTo(500);
    }

    @Test
    @DisplayName("A nonsense limit still yields a bounded query — never an unbounded scan")
    void staleHeldIsAlwaysBounded() {
        when(settlementRepository.findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                any(), any(), any())).thenReturn(List.of());

        service.staleHeld(0);

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(settlementRepository).findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                any(), any(), page.capture());
        assertThat(page.getValue().getPageSize()).isGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------
    // The chokepoint's refusals
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An illegal move is refused 409, counted, and mutates NOTHING")
    void illegalTransitionsRefuseBeforeMutating() {
        MerchantSettlement s = settlement(SettlementStatus.PAID_OUT);

        assertThatThrownBy(() -> service.recordRefund(s, "RFND-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("illegal_settlement_state");
        // Validate-before-mutate is structural: the refused refund left no
        // stamp behind.
        assertThat(s.getRefundedAt()).isNull();
        assertThat(s.getRefundReference()).isNull();
        assertThat(s.getStatus()).isEqualTo(SettlementStatus.PAID_OUT);
    }

    @Test
    @DisplayName("Journal rows land on the ORDER, tagged SETTLEMENT")
    void journalsAgainstTheOrderAsSettlement() {
        MerchantSettlement s = settlement(SettlementStatus.HELD);
        service.freeze(s, DisputeReason.NOT_RECEIVED);

        ArgumentCaptor<MarketOrderEvent> captor = ArgumentCaptor.forClass(MarketOrderEvent.class);
        verify(eventRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getKind()).isEqualTo(MarketOrderEvent.KIND_SETTLEMENT);
        assertThat(captor.getValue().getOrderId()).isEqualTo(ORDER_ID);
        assertThat(captor.getValue().getFromStatus()).isEqualTo("HELD");
        assertThat(captor.getValue().getToStatus()).isEqualTo("DISPUTED");
    }
}
