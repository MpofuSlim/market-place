package com.innbucks.marketplaceservice.settlement;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.fulfilment.ParcelCloseMethod;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.settlement.dto.SettlementResponse;
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

class SettlementViewAssemblerTest {

    private static final UUID SELLER = UUID.randomUUID();
    private static final UUID OTHER_SELLER = UUID.randomUUID();

    private MarketOrderRepository orders;
    private OrderFulfilmentRepository parcels;
    private MarketOrderItemRepository items;
    private SettlementDisputeRepository disputes;
    private SettlementViewAssembler assembler;

    @BeforeEach
    void setUp() {
        orders = mock(MarketOrderRepository.class);
        parcels = mock(OrderFulfilmentRepository.class);
        items = mock(MarketOrderItemRepository.class);
        disputes = mock(SettlementDisputeRepository.class);
        assembler = new SettlementViewAssembler(orders, parcels, items, disputes);
    }

    private static MarketOrderItem item(UUID orderId, UUID merchant, String title, int qty) {
        return MarketOrderItem.builder().id(UUID.randomUUID()).orderId(orderId)
                .listingId(UUID.randomUUID()).merchantId(merchant).titleSnapshot(title)
                .unitPriceCents(100).quantity(qty).lineTotalCents(100L * qty).build();
    }

    @Test
    @DisplayName("A page of rows costs four queries, and each row names its order, items and close")
    void rowsCarryWhatAPersonRecognises() {
        UUID orderId = UUID.randomUUID();
        UUID parcelId = UUID.randomUUID();
        Instant closed = Instant.parse("2026-09-18T09:15:00Z");
        MarketOrder order = new MarketOrder();
        order.setId(orderId);
        order.setOrderRef("MKT-9B3E7D10A4C2");
        order.setDeliveryMethod(DeliveryMethod.DELIVERY);
        OrderFulfilment parcel = OrderFulfilment.builder().id(parcelId).orderId(orderId)
                .merchantId(SELLER).status(FulfilmentStatus.UNFULFILLED).unfulfilledAt(closed)
                .unfulfilledReason("Out of stock").build();
        MerchantSettlement refund = MerchantSettlement.builder().id(UUID.randomUUID())
                .orderId(orderId).fulfilmentId(parcelId).merchantId(SELLER)
                .status(SettlementStatus.REFUND_DUE).grossCents(1550).netCents(1550)
                .currency("USD").createdAt(closed).build();
        when(orders.findAllById(any())).thenReturn(List.of(order));
        when(parcels.findAllById(any())).thenReturn(List.of(parcel));
        when(items.findByOrderIdIn(any())).thenReturn(List.of(
                item(orderId, SELLER, "Solar Lantern 20W", 1),
                item(orderId, OTHER_SELLER, "Garden Hose", 1)));

        SettlementResponse row = assembler.toResponses(List.of(refund)).getFirst();

        assertThat(row.orderRef()).isEqualTo("MKT-9B3E7D10A4C2");
        // Only THIS seller's lines — never what else the buyer bought.
        assertThat(row.itemSummary()).isEqualTo("1 x Solar Lantern 20W");
        assertThat(row.closedBy()).isEqualTo(ParcelCloseMethod.CANNOT_SUPPLY);
        assertThat(row.closedAt()).isEqualTo(closed);
        assertThat(row.refundReason()).isEqualTo("Out of stock");
        verify(orders, times(1)).findAllById(any());
        verify(parcels, times(1)).findAllById(any());
        verify(items, times(1)).findByOrderIdIn(any());
        verify(disputes, times(1)).findByFulfilmentIdIn(any());
    }

    @Test
    @DisplayName("A long order summarises, not sprawls")
    void longOrdersSummarise() {
        UUID orderId = UUID.randomUUID();
        List<MarketOrderItem> five = List.of(item(orderId, SELLER, "A", 1), item(orderId, SELLER, "B", 2),
                item(orderId, SELLER, "C", 1), item(orderId, SELLER, "D", 1), item(orderId, SELLER, "E", 1));

        assertThat(SettlementViewAssembler.itemSummary(five)).isEqualTo("1 x A, 2 x B, 1 x C + 2 more");
        assertThat(SettlementViewAssembler.itemSummary(List.of())).isNull();
    }

    @Test
    @DisplayName("A refund reason appears only where a refund is in play, from whoever decided it")
    void refundReasonSources() {
        MerchantSettlement released = MerchantSettlement.builder()
                .status(SettlementStatus.RELEASABLE).build();
        MerchantSettlement refunded = MerchantSettlement.builder()
                .status(SettlementStatus.REFUNDED).build();
        OrderFulfilment declined = OrderFulfilment.builder().unfulfilledReason("Damaged").build();
        OrderFulfilment delivered = OrderFulfilment.builder().build();
        SettlementDispute lost = new SettlementDispute();
        lost.setStatus(DisputeStatus.REFUNDED);
        lost.setResolutionNote("Courier confirmed the parcel was never collected from the depot");

        assertThat(SettlementViewAssembler.refundReason(released, declined, null)).isNull();
        assertThat(SettlementViewAssembler.refundReason(refunded, declined, null)).isEqualTo("Damaged");
        assertThat(SettlementViewAssembler.refundReason(refunded, delivered, lost))
                .isEqualTo("Courier confirmed the parcel was never collected from the depot");
    }
}
