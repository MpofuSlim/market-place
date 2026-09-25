package com.innbucks.marketplaceservice.settlement;

import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.fulfilment.ParcelCloseMethod;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.settlement.dto.ParcelDisputeSummary;
import com.innbucks.marketplaceservice.settlement.dto.SettlementResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the seller's EARNINGS rows: each parcel's money, plus what a person
 * needs to recognise it — the order reference the buyer quotes, what was in
 * it, how it closed, and why a refund or dispute happened. Four batch queries
 * for a whole page (orders, parcels, lines, disputes), however long.
 */
@Component
@RequiredArgsConstructor
public class SettlementViewAssembler {

    /** Lines named on a row before it says "+ N more". */
    static final int SUMMARY_LINES = 3;

    private final MarketOrderRepository orderRepository;
    private final OrderFulfilmentRepository fulfilmentRepository;
    private final MarketOrderItemRepository itemRepository;
    private final SettlementDisputeRepository disputeRepository;

    public SettlementResponse toResponse(MerchantSettlement settlement) {
        return toResponses(List.of(settlement)).getFirst();
    }

    public List<SettlementResponse> toResponses(List<MerchantSettlement> settlements) {
        if (settlements.isEmpty()) {
            return List.of();
        }
        List<UUID> orderIds = settlements.stream().map(MerchantSettlement::getOrderId)
                .distinct().toList();
        List<UUID> parcelIds = settlements.stream().map(MerchantSettlement::getFulfilmentId).toList();
        Map<UUID, MarketOrder> orders = orderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(MarketOrder::getId, Function.identity()));
        Map<UUID, OrderFulfilment> parcels = fulfilmentRepository.findAllById(parcelIds).stream()
                .collect(Collectors.toMap(OrderFulfilment::getId, Function.identity()));
        Map<UUID, List<MarketOrderItem>> itemsByOrder = itemRepository.findByOrderIdIn(orderIds)
                .stream().collect(Collectors.groupingBy(MarketOrderItem::getOrderId));
        Map<UUID, SettlementDispute> disputes = disputeRepository.findByFulfilmentIdIn(parcelIds)
                .stream()
                .collect(Collectors.toMap(SettlementDispute::getFulfilmentId, Function.identity()));

        List<SettlementResponse> rows = new ArrayList<>(settlements.size());
        for (MerchantSettlement s : settlements) {
            MarketOrder order = orders.get(s.getOrderId());
            OrderFulfilment parcel = parcels.get(s.getFulfilmentId());
            SettlementDispute dispute = disputes.get(s.getFulfilmentId());
            List<MarketOrderItem> mine = itemsByOrder.getOrDefault(s.getOrderId(), List.of())
                    .stream().filter(item -> s.getMerchantId().equals(item.getMerchantId()))
                    .toList();
            rows.add(SettlementResponse.from(s, new SettlementResponse.Context(
                    order == null ? null : order.getOrderRef(),
                    itemSummary(mine),
                    order == null ? null : ParcelCloseMethod.of(parcel, order.getDeliveryMethod()),
                    ParcelCloseMethod.closedAt(parcel),
                    refundReason(s, parcel, dispute),
                    ParcelDisputeSummary.of(dispute))));
        }
        return rows;
    }

    /**
     * "2 x Solar Lantern 20W, 1 x Garden Hose + 2 more" — enough to recognise
     * a sale at a glance, bounded so a thirty-line order is still one row.
     */
    static String itemSummary(List<MarketOrderItem> items) {
        if (items.isEmpty()) {
            return null;
        }
        String named = items.stream().limit(SUMMARY_LINES)
                .map(item -> item.getQuantity() + " x " + item.displayTitle())
                .collect(Collectors.joining(", "));
        return items.size() > SUMMARY_LINES
                ? named + " + " + (items.size() - SUMMARY_LINES) + " more" : named;
    }

    /**
     * Why this money went (or is going) back to the buyer: the seller's own
     * reason when they declined the parcel, the buyer's when they cancelled it
     * (V16), the operator's note when a dispute was decided for the buyer. Null on every row where no refund is in play.
     */
    static String refundReason(MerchantSettlement s, OrderFulfilment parcel,
                               SettlementDispute dispute) {
        if (s.getStatus() != SettlementStatus.REFUND_DUE
                && s.getStatus() != SettlementStatus.REFUNDED) {
            return null;
        }
        if (parcel != null && parcel.getUnfulfilledReason() != null) {
            return parcel.getUnfulfilledReason();
        }
        if (parcel != null && parcel.getUnfulfilledBy()
                == com.innbucks.marketplaceservice.fulfilment.UnfulfilledBy.BUYER) {
            // A buyer need not say why; the row must still say what happened.
            return "Cancelled by the buyer";
        }
        if (dispute != null && dispute.getStatus() == DisputeStatus.REFUNDED) {
            return dispute.getResolutionNote();
        }
        return null;
    }
}
