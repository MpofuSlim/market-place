package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.collect.CollectCodeAttempts;
import com.innbucks.marketplaceservice.fulfilment.collect.CollectCodes;
import com.innbucks.marketplaceservice.fulfilment.dto.CollectCodeResponse;
import com.innbucks.marketplaceservice.fulfilment.dto.CollectRequest;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.notify.CollectCodeNotifier;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderEventRepository;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.order.OrderStatus;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.settlement.SettlementService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
 * Pins the collection handover code: who may mint one, who may redeem one, and
 * what a seller working through candidates runs into.
 */
class CollectCodeServiceTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID MERCHANT_A = UUID.randomUUID();
    private static final UUID BUYER_UUID = UUID.randomUUID();

    private static final AuthenticatedUser BUYER = new AuthenticatedUser(
            BUYER_UUID.toString(), Set.of("CUSTOMER"), null, null, "+263771234567", "ZW");
    private static final AuthenticatedUser SELLER = new AuthenticatedUser(
            UUID.randomUUID().toString(), Set.of("MERCHANT_ADMIN"),
            MERCHANT_A.toString(), null, null, "ZW");

    private OrderFulfilmentRepository fulfilmentRepository;
    private MarketOrderRepository orderRepository;
    private MarketOrderItemRepository itemRepository;
    private SettlementService settlementService;
    private AuditService auditService;
    private CollectCodeAttempts attempts;
    private CollectCodeNotifier notifier;
    private SimpleMeterRegistry registry;
    private FulfilmentService service;

    @BeforeEach
    void setUp() {
        fulfilmentRepository = mock(OrderFulfilmentRepository.class);
        orderRepository = mock(MarketOrderRepository.class);
        itemRepository = mock(MarketOrderItemRepository.class);
        settlementService = mock(SettlementService.class);
        auditService = mock(AuditService.class);
        attempts = mock(CollectCodeAttempts.class);
        notifier = mock(CollectCodeNotifier.class);
        registry = new SimpleMeterRegistry();
        service = new FulfilmentService(fulfilmentRepository, orderRepository, itemRepository,
                mock(MarketOrderEventRepository.class), settlementService, auditService,
                new MarketplaceMetrics(registry), attempts, notifier,
                mock(ParcelStockReturner.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
        ReflectionTestUtils.setField(service, "maxCollectAttempts", MAX_ATTEMPTS);
        when(fulfilmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.findByOrderId(ORDER_ID)).thenReturn(List.of(
                MarketOrderItem.builder().id(UUID.randomUUID()).orderId(ORDER_ID)
                        .listingId(UUID.randomUUID()).merchantId(MERCHANT_A)
                        .titleSnapshot("Solar Lantern 20W").unitPriceCents(1550)
                        .quantity(2).lineTotalCents(3100).build()));
    }

    private MarketOrder order(DeliveryMethod method, String recipientName,
                             String recipientMsisdn) {
        Instant now = Instant.now();
        MarketOrder order = MarketOrder.builder()
                .id(ORDER_ID).orderRef("MKT-4F9A1C22B7D3").buyerUuid(BUYER_UUID)
                .buyerMsisdn("+263771234567").status(OrderStatus.PAID)
                .subtotalCents(3100).deliveryFeeCents(0).totalCents(3100).currency("USD")
                .deliveryMethod(method)
                .recipientName(recipientName).recipientMsisdn(recipientMsisdn)
                .expiresAt(now).paidAt(now).createdAt(now).updatedAt(now).build();
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private OrderFulfilment parcel(FulfilmentStatus status) {
        Instant now = Instant.now();
        OrderFulfilment p = OrderFulfilment.builder()
                .id(UUID.randomUUID()).orderId(ORDER_ID).merchantId(MERCHANT_A).status(status)
                .createdAt(now).updatedAt(now).version(0L).build();
        when(fulfilmentRepository.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    private double outcome(String tag) {
        var counter = registry.find("marketplace.collect_codes").tag("outcome", tag).counter();
        return counter == null ? 0.0 : counter.count();
    }

    // ------------------------------------------------------------------
    // Minting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The buyer gets the plaintext once; the parcel keeps only its hash")
    void mintReturnsThePlaintextAndStoresOnlyTheHash() {
        order(DeliveryMethod.COLLECTION, "Gogo Chipo Moyo", "+263772345678");
        OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);

        CollectCodeResponse response = service.mintCollectCode(BUYER, ORDER_ID, parcel.getId());

        assertThat(response.code()).hasSize(12);
        assertThat(response.groupedCode()).isEqualTo(CollectCodes.grouped(response.code()));
        assertThat(parcel.getCollectCodeHash())
                .isEqualTo(CollectCodes.hash(response.code()))
                .doesNotContain(response.code());
        assertThat(parcel.getCollectCodeIssuedAt()).isNotNull();
        assertThat(outcome("minted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("The code goes to the RECIPIENT when the order names one, else to the buyer")
    void theCodeIsSentToWhoeverIsCollecting() {
        order(DeliveryMethod.COLLECTION, "Gogo Chipo Moyo", "+263772345678");
        OrderFulfilment gift = parcel(FulfilmentStatus.DISPATCHED);
        when(notifier.send(eq("+263772345678"), anyString(), anyString())).thenReturn("****5678");

        assertThat(service.mintCollectCode(BUYER, ORDER_ID, gift.getId()).sentTo())
                .isEqualTo("****5678");

        order(DeliveryMethod.COLLECTION, null, null);
        OrderFulfilment own = parcel(FulfilmentStatus.DISPATCHED);
        service.mintCollectCode(BUYER, ORDER_ID, own.getId());
        verify(notifier).send(eq("+263771234567"), anyString(), anyString());
    }

    @Test
    @DisplayName("A gateway that swallowed the message is reported honestly, and the code still returns")
    void aFailedSendStillReturnsTheCode() {
        order(DeliveryMethod.COLLECTION, null, null);
        OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);
        when(notifier.send(anyString(), anyString(), anyString())).thenReturn(null);

        CollectCodeResponse response = service.mintCollectCode(BUYER, ORDER_ID, parcel.getId());

        assertThat(response.sentTo()).isNull();
        assertThat(response.code()).isNotBlank();
    }

    @Test
    @DisplayName("Minting again replaces the live code and refills the wrong-code budget")
    void reMintingRotatesTheCodeAndResetsTheBudget() {
        order(DeliveryMethod.COLLECTION, null, null);
        OrderFulfilment parcel = parcel(FulfilmentStatus.DISPATCHED);
        String first = service.mintCollectCode(BUYER, ORDER_ID, parcel.getId()).code();
        parcel.setCollectCodeAttempts(MAX_ATTEMPTS);

        String second = service.mintCollectCode(BUYER, ORDER_ID, parcel.getId()).code();

        assertThat(second).isNotEqualTo(first);
        // The old code is dead the moment the new one exists.
        assertThat(CollectCodes.matches(first, parcel.getCollectCodeHash())).isFalse();
        assertThat(CollectCodes.matches(second, parcel.getCollectCodeHash())).isTrue();
        assertThat(parcel.getCollectCodeAttempts()).isZero();
    }

    @Test
    @DisplayName("Another buyer's parcel is the same 404 as one that does not exist")
    void mintingIsOwnerMasked() {
        order(DeliveryMethod.COLLECTION, null, null);
        OrderFulfilment parcel = parcel(FulfilmentStatus.PREPARING);
        AuthenticatedUser stranger = new AuthenticatedUser(UUID.randomUUID().toString(),
                Set.of("CUSTOMER"), null, null, "+263779999999", "ZW");

        assertThatThrownBy(() -> service.mintCollectCode(stranger, ORDER_ID, parcel.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("fulfilment_not_found");
    }

    @Test
    @DisplayName("There is nothing to collect on a delivery order, or on a parcel already handed over")
    void mintingRefusesWhereACodeMeansNothing() {
        order(DeliveryMethod.DELIVERY, null, null);
        OrderFulfilment delivery = parcel(FulfilmentStatus.PREPARING);
        assertThatThrownBy(() -> service.mintCollectCode(BUYER, ORDER_ID, delivery.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("collect_code_not_applicable");

        order(DeliveryMethod.COLLECTION, null, null);
        OrderFulfilment done = parcel(FulfilmentStatus.DELIVERED);
        assertThatThrownBy(() -> service.mintCollectCode(BUYER, ORDER_ID, done.getId()))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("illegal_fulfilment_state");
    }

    // ------------------------------------------------------------------
    // Redeeming
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A redeemed code closes the parcel as RECIPIENT and releases the money")
    void redeemingClosesTheParcelAsRecipient() {
        order(DeliveryMethod.COLLECTION, "Gogo Chipo Moyo", "+263772345678");
        OrderFulfilment parcel = parcel(FulfilmentStatus.DISPATCHED);
        String code = service.mintCollectCode(BUYER, ORDER_ID, parcel.getId()).code();

        service.collect(SELLER, parcel.getId(), new CollectRequest(CollectCodes.grouped(code)));

        assertThat(parcel.getStatus()).isEqualTo(FulfilmentStatus.DELIVERED);
        assertThat(parcel.getDeliveredBy()).isEqualTo(DeliveryConfirmer.RECIPIENT);
        assertThat(parcel.getCollectCodeRedeemedAt()).isNotNull();
        // The escrow reacts in the same transaction as the handover.
        verify(settlementService).onParcelDelivered(parcel);
        assertThat(outcome("redeemed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("A wrong code spends a slot of the budget and closes nothing")
    void aWrongCodeIsCountedAndChangesNothing() {
        order(DeliveryMethod.COLLECTION, null, null);
        OrderFulfilment parcel = parcel(FulfilmentStatus.DISPATCHED);
        service.mintCollectCode(BUYER, ORDER_ID, parcel.getId());
        when(attempts.bumpAndCount(parcel.getId())).thenReturn(1);

        assertThatThrownBy(() -> service.collect(SELLER, parcel.getId(),
                new CollectRequest("AAAA-BBBB-CCCC")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("collect_code_invalid");

        verify(attempts).bumpAndCount(parcel.getId());
        assertThat(parcel.getStatus()).isEqualTo(FulfilmentStatus.DISPATCHED);
        assertThat(parcel.getDeliveredBy()).isNull();
        verify(settlementService, never()).onParcelDelivered(any());
        assertThat(outcome("invalid")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Spending the last slot locks the parcel and leaves one audit row")
    void exhaustingTheBudgetLocksAndAudits() {
        order(DeliveryMethod.COLLECTION, null, null);
        OrderFulfilment parcel = parcel(FulfilmentStatus.DISPATCHED);
        service.mintCollectCode(BUYER, ORDER_ID, parcel.getId());
        when(attempts.bumpAndCount(parcel.getId())).thenReturn(MAX_ATTEMPTS);

        assertThatThrownBy(() -> service.collect(SELLER, parcel.getId(),
                new CollectRequest("AAAA-BBBB-CCCC")))
                .isInstanceOf(ApiException.class);
        verify(auditService).record(eq(AuditEventType.COLLECT_CODE_LOCKED), anyString(),
                eq(parcel.getId().toString()), anyMap());

        // Once locked, the budget is spent: further attempts are refused
        // before the compare, so a locked parcel cannot flood the audit chain.
        parcel.setCollectCodeAttempts(MAX_ATTEMPTS);
        assertThatThrownBy(() -> service.collect(SELLER, parcel.getId(),
                new CollectRequest("AAAA-BBBB-CCCC")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("collect_code_locked");
        verify(auditService).record(eq(AuditEventType.COLLECT_CODE_LOCKED), anyString(),
                anyString(), anyMap());
    }

    @Test
    @DisplayName("A locked parcel refuses even the RIGHT code — the buyer mints a fresh one")
    void aLockedParcelRefusesTheCorrectCodeToo() {
        order(DeliveryMethod.COLLECTION, null, null);
        OrderFulfilment parcel = parcel(FulfilmentStatus.DISPATCHED);
        String code = service.mintCollectCode(BUYER, ORDER_ID, parcel.getId()).code();
        parcel.setCollectCodeAttempts(MAX_ATTEMPTS);

        assertThatThrownBy(() -> service.collect(SELLER, parcel.getId(), new CollectRequest(code)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("collect_code_locked");
        verify(attempts, never()).bumpAndCount(any());
    }

    @Test
    @DisplayName("A parcel with no code issued refuses without spending anything")
    void withoutACodeThereIsNothingToRedeem() {
        order(DeliveryMethod.COLLECTION, null, null);
        OrderFulfilment parcel = parcel(FulfilmentStatus.DISPATCHED);

        assertThatThrownBy(() -> service.collect(SELLER, parcel.getId(),
                new CollectRequest("K7Q2-9XMF-3TRW")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("collect_code_unavailable");
        verify(attempts, never()).bumpAndCount(any());
    }

    @Test
    @DisplayName("Redeeming the same code twice is a double-tap, not a wasted attempt")
    void aSecondRedemptionCostsNoBudget() {
        order(DeliveryMethod.COLLECTION, null, null);
        OrderFulfilment parcel = parcel(FulfilmentStatus.DISPATCHED);
        String code = service.mintCollectCode(BUYER, ORDER_ID, parcel.getId()).code();
        service.collect(SELLER, parcel.getId(), new CollectRequest(code));

        assertThatThrownBy(() -> service.collect(SELLER, parcel.getId(), new CollectRequest(code)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("illegal_fulfilment_state");
        verify(attempts, never()).bumpAndCount(any());
    }

    @Test
    @DisplayName("Another seller cannot redeem against a parcel that is not theirs")
    void redeemingIsSellerScoped() {
        order(DeliveryMethod.COLLECTION, null, null);
        OrderFulfilment parcel = parcel(FulfilmentStatus.DISPATCHED);
        String code = service.mintCollectCode(BUYER, ORDER_ID, parcel.getId()).code();
        AuthenticatedUser otherSeller = new AuthenticatedUser(UUID.randomUUID().toString(),
                Set.of("MERCHANT_ADMIN"), UUID.randomUUID().toString(), null, null, "ZW");

        assertThatThrownBy(() -> service.collect(otherSeller, parcel.getId(),
                new CollectRequest(code)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo("fulfilment_not_found");
    }

    @Test
    @DisplayName("The seller's view carries the collector's NAME and never the code")
    void theSellerSeesWhoIsComingButNotTheCode() {
        order(DeliveryMethod.COLLECTION, "Gogo Chipo Moyo", "+263772345678");
        OrderFulfilment parcel = parcel(FulfilmentStatus.DISPATCHED);
        String code = service.mintCollectCode(BUYER, ORDER_ID, parcel.getId()).code();

        var view = service.collect(SELLER, parcel.getId(), new CollectRequest(code));

        assertThat(view.collectorName()).isEqualTo("Gogo Chipo Moyo");
        assertThat(view.collectCodeRedeemedAt()).isNotNull();
        assertThat(view.collectCodeIssued()).isFalse();
        assertThat(view.toString()).doesNotContain(code);
    }
}
