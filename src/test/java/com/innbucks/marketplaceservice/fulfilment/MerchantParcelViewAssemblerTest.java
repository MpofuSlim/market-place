package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentResponse;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.settlement.DisputeReason;
import com.innbucks.marketplaceservice.settlement.DisputeStatus;
import com.innbucks.marketplaceservice.settlement.MerchantSettlement;
import com.innbucks.marketplaceservice.settlement.MerchantSettlementRepository;
import com.innbucks.marketplaceservice.settlement.SettlementDispute;
import com.innbucks.marketplaceservice.settlement.SettlementDisputeRepository;
import com.innbucks.marketplaceservice.settlement.SettlementStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantParcelViewAssemblerTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID SELLER = UUID.randomUUID();

    private MarketOrderRepository orders;
    private MarketOrderItemRepository items;
    private MerchantSettlementRepository settlements;
    private SettlementDisputeRepository disputes;
    private MerchantParcelViewAssembler assembler;

    @BeforeEach
    void setUp() {
        orders = mock(MarketOrderRepository.class);
        items = mock(MarketOrderItemRepository.class);
        settlements = mock(MerchantSettlementRepository.class);
        disputes = mock(SettlementDisputeRepository.class);
        assembler = new MerchantParcelViewAssembler(orders, items, settlements, disputes,
                mock(com.innbucks.marketplaceservice.pickup.CollectionPointViews.class), 10);
        MarketOrder order = new MarketOrder();
        order.setId(ORDER);
        order.setOrderRef("MKT-4F9A1C22B7D3");
        order.setDeliveryMethod(DeliveryMethod.COLLECTION);
        order.setCurrency("USD");
        when(orders.findAllById(any())).thenReturn(List.of(order));
    }

    private OrderFulfilment parcel(FulfilmentStatus status) {
        return OrderFulfilment.builder().id(UUID.randomUUID()).orderId(ORDER).merchantId(SELLER)
                .status(status).createdAt(Instant.now()).trackingCode("TRK-7F3K9Q2M4X").build();
    }

    @Test
    @DisplayName("A whole page of cards is four queries, not three per card")
    void pageIsBatched() {
        assembler.toViews(List.of(parcel(FulfilmentStatus.PREPARING),
                parcel(FulfilmentStatus.PREPARING), parcel(FulfilmentStatus.PREPARING)));

        verify(orders, times(1)).findAllById(any());
        verify(items, times(1)).findByOrderIdIn(any());
        verify(settlements, times(1)).findByFulfilmentIdIn(any());
        verify(disputes, times(1)).findByFulfilmentIdIn(any());
    }

    @Test
    @DisplayName("The clearing date shows only while the money is HELD against it")
    void clearsAtOnlyWhileHeld() {
        OrderFulfilment p = parcel(FulfilmentStatus.DELIVERED);
        Instant clears = Instant.parse("2026-10-01T14:05:00Z");
        MerchantSettlement held = MerchantSettlement.builder().fulfilmentId(p.getId())
                .status(SettlementStatus.HELD).netCents(4798).releasableAt(clears).build();
        when(settlements.findByFulfilmentIdIn(any())).thenReturn(List.of(held));

        assertThat(assembler.toView(p).settlementClearsAt()).isEqualTo(clears);

        held.setStatus(SettlementStatus.RELEASABLE);
        assertThat(assembler.toView(p).settlementClearsAt()).isNull();
    }

    @Test
    @DisplayName("A live collection code says how many wrong tries are left, and when it has locked")
    void collectCodeBudget() {
        OrderFulfilment p = parcel(FulfilmentStatus.DISPATCHED);
        assertThat(assembler.toView(p).collectCodeLocked()).isNull();
        assertThat(assembler.toView(p).collectCodeAttemptsLeft()).isNull();

        p.setCollectCodeHash("h");
        p.setCollectCodeAttempts(3);
        MerchantFulfilmentResponse live = assembler.toView(p);
        assertThat(live.collectCodeLocked()).isFalse();
        assertThat(live.collectCodeAttemptsLeft()).isEqualTo(7);

        p.setCollectCodeAttempts(10);
        MerchantFulfilmentResponse locked = assembler.toView(p);
        assertThat(locked.collectCodeLocked()).isTrue();
        assertThat(locked.collectCodeAttemptsLeft()).isZero();
    }

    @Test
    @DisplayName("A dispute on the parcel explains the hold: status, reason and dates")
    void disputeRidesTheCard() {
        OrderFulfilment p = parcel(FulfilmentStatus.DISPATCHED);
        SettlementDispute dispute = new SettlementDispute();
        dispute.setFulfilmentId(p.getId());
        dispute.setStatus(DisputeStatus.OPEN);
        dispute.setReason(DisputeReason.NOT_RECEIVED);
        dispute.setCreatedAt(Instant.parse("2026-09-20T10:00:00Z"));
        dispute.setDetail("the buyer's own words, which the seller does not get");
        when(disputes.findByFulfilmentIdIn(any())).thenReturn(List.of(dispute));

        MerchantFulfilmentResponse card = assembler.toView(p);

        assertThat(card.dispute().status()).isEqualTo(DisputeStatus.OPEN);
        assertThat(card.dispute().reason()).isEqualTo(DisputeReason.NOT_RECEIVED);
        assertThat(card.dispute().openedAt()).isEqualTo(Instant.parse("2026-09-20T10:00:00Z"));
    }
}
