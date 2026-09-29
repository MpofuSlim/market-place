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
    private com.innbucks.marketplaceservice.pickup.CollectionPointViews collectionPoints;
    private MarketOrder order;

    @BeforeEach
    void setUp() {
        orders = mock(MarketOrderRepository.class);
        items = mock(MarketOrderItemRepository.class);
        settlements = mock(MerchantSettlementRepository.class);
        disputes = mock(SettlementDisputeRepository.class);
        collectionPoints = mock(com.innbucks.marketplaceservice.pickup.CollectionPointViews.class);
        assembler = new MerchantParcelViewAssembler(orders, items, settlements, disputes,
                collectionPoints, 10);
        order = new MarketOrder();
        order.setId(ORDER);
        order.setOrderRef("MKT-4F9A1C22B7D3");
        order.setDeliverySummary(DeliveryMethod.COLLECTION);
        order.setCurrency("USD");
        when(orders.findAllById(any())).thenReturn(List.of(order));
    }

    private OrderFulfilment parcel(FulfilmentStatus status) {
        return parcel(status, DeliveryMethod.COLLECTION);
    }

    private OrderFulfilment parcel(FulfilmentStatus status, DeliveryMethod method) {
        return OrderFulfilment.builder().id(UUID.randomUUID()).orderId(ORDER).merchantId(SELLER)
                .status(status).deliveryMethod(method).createdAt(Instant.now())
                .trackingCode("TRK-7F3K9Q2M4X").build();
    }

    /** A mixed basket's order: summary DELIVERY with a destination (another
     *  seller delivers), bought as a gift for someone who collects. */
    private void mixedOrder() {
        order.setDeliverySummary(DeliveryMethod.DELIVERY);
        order.setDeliveryRecipientName("Tariro Moyo");
        order.setDeliveryRecipientMsisdn("+263771234567");
        order.setDeliveryLine1("14 Samora Machel Ave");
        order.setDeliveryCity("Harare");
        order.setRecipientName("Gogo Chipo Moyo");
    }

    @Test
    @DisplayName("MIXED: a COLLECTION parcel's card has no destination, names the collector, and closes as NOT_COLLECTED")
    void mixedCollectedCardReadsTheParcel() {
        mixedOrder();
        OrderFulfilment collected = parcel(FulfilmentStatus.UNFULFILLED, DeliveryMethod.COLLECTION);
        collected.setDispatchedAt(Instant.parse("2026-09-20T10:00:00Z"));
        collected.setUnfulfilledAt(Instant.parse("2026-09-28T10:00:00Z"));
        collected.setUnfulfilledBy(UnfulfilledBy.SELLER);

        MerchantFulfilmentResponse card = assembler.toView(collected);

        assertThat(card.deliveryMethod()).isEqualTo(DeliveryMethod.COLLECTION);
        // The delivering seller's destination is none of this seller's business.
        assertThat(card.destination()).isNull();
        assertThat(card.collectorName()).isEqualTo("Gogo Chipo Moyo");
        assertThat(card.closedBy()).isEqualTo(ParcelCloseMethod.NOT_COLLECTED);
        // Its collection point is looked up, although the order's summary is DELIVERY.
        verify(collectionPoints).snapshotsFor(List.of(ORDER));
    }

    @Test
    @DisplayName("MIXED: the DELIVERY sibling's card carries the destination, names no collector, and asks for no collection point")
    void mixedDeliveredCardReadsTheParcel() {
        mixedOrder();
        OrderFulfilment delivered = parcel(FulfilmentStatus.DISPATCHED, DeliveryMethod.DELIVERY);

        MerchantFulfilmentResponse card = assembler.toView(delivered);

        assertThat(card.deliveryMethod()).isEqualTo(DeliveryMethod.DELIVERY);
        assertThat(card.destination()).isNotNull();
        assertThat(card.destination().line1()).isEqualTo("14 Samora Machel Ave");
        assertThat(card.collectorName()).isNull();
        assertThat(card.collectionPoint()).isNull();
        verify(collectionPoints).snapshotsFor(List.of());
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
