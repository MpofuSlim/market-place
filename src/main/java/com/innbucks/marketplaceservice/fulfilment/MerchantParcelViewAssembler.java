package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.dto.FulfilmentDestination;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentResponse;
import com.innbucks.marketplaceservice.fulfilment.notice.BuyerNoticeView;
import com.innbucks.marketplaceservice.fulfilment.tracking.ParcelLocation;
import com.innbucks.marketplaceservice.fulfilment.tracking.TrackingStatus;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.pickup.CollectionPointViews;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointResponse;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import com.innbucks.marketplaceservice.settlement.MerchantSettlement;
import com.innbucks.marketplaceservice.settlement.MerchantSettlementRepository;
import com.innbucks.marketplaceservice.settlement.SettlementDispute;
import com.innbucks.marketplaceservice.settlement.SettlementDisputeRepository;
import com.innbucks.marketplaceservice.settlement.SettlementStatus;
import com.innbucks.marketplaceservice.settlement.dto.ParcelDisputeSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the SELLER's parcel cards for a whole page in four queries — orders,
 * lines, settlements, disputes — however long the page. The card used to cost
 * three queries per parcel, and every field the portal asked for on top of it
 * (clearing date, dispute) would have added another per parcel.
 *
 * <p>Single-parcel paths (an action's response, a tracking-code lookup) go
 * through the same method with a list of one, so a card can never look
 * different depending on which screen asked for it.
 */
@Component
public class MerchantParcelViewAssembler {

    private final MarketOrderRepository orderRepository;
    private final MarketOrderItemRepository itemRepository;
    private final MerchantSettlementRepository settlementRepository;
    private final SettlementDisputeRepository disputeRepository;
    private final CollectionPointViews collectionPoints;
    private final int maxCollectAttempts;

    public MerchantParcelViewAssembler(MarketOrderRepository orderRepository,
                                       MarketOrderItemRepository itemRepository,
                                       MerchantSettlementRepository settlementRepository,
                                       SettlementDisputeRepository disputeRepository,
                                       CollectionPointViews collectionPoints,
                                       @Value("${marketplace.fulfilment.collect-code-max-attempts}")
                                       int maxCollectAttempts) {
        this.orderRepository = orderRepository;
        this.itemRepository = itemRepository;
        this.settlementRepository = settlementRepository;
        this.disputeRepository = disputeRepository;
        this.collectionPoints = collectionPoints;
        this.maxCollectAttempts = maxCollectAttempts;
    }

    public MerchantFulfilmentResponse toView(OrderFulfilment parcel) {
        return toViews(List.of(parcel)).getFirst();
    }

    /** Cards in the order given. A parcel whose order cannot be read is an
     *  integrity fault, not a missing card: it throws, as the per-parcel read
     *  always did. */
    public List<MerchantFulfilmentResponse> toViews(List<OrderFulfilment> parcels) {
        if (parcels.isEmpty()) {
            return List.of();
        }
        List<UUID> orderIds = parcels.stream().map(OrderFulfilment::getOrderId).distinct().toList();
        List<UUID> parcelIds = parcels.stream().map(OrderFulfilment::getId).toList();
        Map<UUID, MarketOrder> orders = orderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(MarketOrder::getId, Function.identity()));
        Map<UUID, List<MarketOrderItem>> itemsByOrder = itemRepository.findByOrderIdIn(orderIds)
                .stream().collect(Collectors.groupingBy(MarketOrderItem::getOrderId));
        Map<UUID, MerchantSettlement> settlements = settlementRepository
                .findByFulfilmentIdIn(parcelIds).stream()
                .collect(Collectors.toMap(MerchantSettlement::getFulfilmentId, Function.identity()));
        Map<UUID, SettlementDispute> disputes = disputeRepository.findByFulfilmentIdIn(parcelIds)
                .stream()
                .collect(Collectors.toMap(SettlementDispute::getFulfilmentId, Function.identity()));
        // Where each COLLECTION parcel is being collected: one snapshot query
        // for the page, never one per card.
        Map<UUID, Map<UUID, CollectionPointResponse>> points = collectionPoints.snapshotsFor(
                orders.values().stream()
                        .filter(o -> o.getDeliveryMethod() == DeliveryMethod.COLLECTION)
                        .map(MarketOrder::getId).toList());

        List<MerchantFulfilmentResponse> views = new ArrayList<>(parcels.size());
        for (OrderFulfilment parcel : parcels) {
            MarketOrder order = orders.get(parcel.getOrderId());
            if (order == null) {
                throw new IllegalStateException("Parcel " + parcel.getId()
                        + " names an order that does not exist: " + parcel.getOrderId());
            }
            views.add(view(parcel, order,
                    itemsByOrder.getOrDefault(order.getId(), List.of()),
                    settlements.get(parcel.getId()), disputes.get(parcel.getId()),
                    points.getOrDefault(order.getId(), Map.of()).get(parcel.getMerchantId())));
        }
        return views;
    }

    private MerchantFulfilmentResponse view(OrderFulfilment parcel, MarketOrder order,
                                            List<MarketOrderItem> orderItems,
                                            MerchantSettlement settlement,
                                            SettlementDispute dispute,
                                            CollectionPointResponse collectionPoint) {
        // Only THIS seller's lines: a seller in a multi-seller order learns
        // nothing about what else the buyer bought.
        List<MarketOrderItem> mine = orderItems.stream()
                .filter(item -> parcel.getMerchantId().equals(item.getMerchantId()))
                .toList();
        long subtotal = mine.stream().mapToLong(MarketOrderItem::getLineTotalCents).sum();
        boolean codeLive = parcel.getCollectCodeHash() != null
                && parcel.getCollectCodeRedeemedAt() == null;
        return new MerchantFulfilmentResponse(
                parcel.getId(),
                order.getId(),
                order.getOrderRef(),
                parcel.getMerchantId(),
                parcel.getStatus(),
                order.getDeliveryMethod(),
                FulfilmentDestination.from(order),
                mine.stream().map(MerchantParcelViewAssembler::toLine).toList(),
                subtotal,
                order.getCurrency(),
                order.getPaidAt(),
                parcel.getDispatchNote(),
                parcel.getDispatchedAt(),
                parcel.getDeliveredAt(),
                parcel.getDeliveredBy(),
                parcel.getCreatedAt(),
                settlement == null ? null : settlement.getStatus(),
                settlement == null ? null : settlement.getNetCents(),
                // Only where somebody is actually coming to a counter: on a
                // DELIVERY order the destination block already names who the
                // courier hands to, and a second name beside it would read as
                // a second person.
                order.getDeliveryMethod() == DeliveryMethod.COLLECTION
                        ? order.getRecipientName() : null,
                codeLive,
                parcel.getCollectCodeRedeemedAt(),
                parcel.getUnfulfilledReason(),
                parcel.getUnfulfilledAt(),
                parcel.getTrackingCode(),
                TrackingStatus.of(parcel.getStatus()),
                parcel.getDeliveryFeeCents(),
                ParcelLocation.of(parcel),
                ParcelCloseMethod.of(parcel, order.getDeliveryMethod()),
                // A clearing date only means something while the money is
                // HELD against it; once released the date is history.
                settlement != null && settlement.getStatus() == SettlementStatus.HELD
                        ? settlement.getReleasableAt() : null,
                ParcelDisputeSummary.of(dispute),
                codeLive ? parcel.getCollectCodeAttempts() >= maxCollectAttempts : null,
                codeLive ? Math.max(0, maxCollectAttempts - parcel.getCollectCodeAttempts()) : null,
                BuyerNoticeView.of(parcel),
                collectionPoint);
    }

    static OrderResponse.Line toLine(MarketOrderItem item) {
        return new OrderResponse.Line(item.getListingId(), item.getTitleSnapshot(),
                item.getUnitPriceCents(), item.getQuantity(), item.getLineTotalCents());
    }
}
