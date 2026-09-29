package com.innbucks.marketplaceservice.order;

import com.innbucks.marketplaceservice.checkout.CheckoutService;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.BuyerParcelRules;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentService;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.fulfilment.dto.FulfilmentDestination;
import com.innbucks.marketplaceservice.fulfilment.dto.FulfilmentResponse;
import com.innbucks.marketplaceservice.fulfilment.tracking.TrackingStatus;
import com.innbucks.marketplaceservice.order.dto.OrderActions;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import com.innbucks.marketplaceservice.pickup.CollectionPointViews;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointResponse;
import com.innbucks.marketplaceservice.seller.MarketplaceSeller;
import com.innbucks.marketplaceservice.seller.SellerService;
import com.innbucks.marketplaceservice.settlement.MerchantSettlement;
import com.innbucks.marketplaceservice.settlement.MerchantSettlementRepository;
import com.innbucks.marketplaceservice.settlement.SettlementDispute;
import com.innbucks.marketplaceservice.settlement.SettlementDisputeRepository;
import com.innbucks.marketplaceservice.settlement.dto.DisputeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the buyer-facing {@link OrderResponse} — the ONE place an order turns
 * into its wire shape, so the single read, the paged list and the replayed
 * idempotent create can never render the same order differently.
 *
 * <p>Batches everything a page needs: lines, parcels, seller names and each
 * seller's method and fee cost ONE query each for the whole page regardless of
 * its size. A per-order assembly here would be an N+1 several times over on the
 * my-orders screen, which is the screen a shopper opens most.
 */
@Component
@RequiredArgsConstructor
public class OrderViewAssembler {

    private final MarketOrderItemRepository itemRepository;
    private final FulfilmentService fulfilmentService;
    private final SettlementDisputeRepository disputeRepository;
    private final SellerService sellerService;
    private final CheckoutService checkoutService;
    private final CollectionPointViews collectionPoints;
    private final MerchantSettlementRepository settlementRepository;
    private final BuyerParcelRules buyerRules;
    private final MarketOrderSellerRepository orderSellerRepository;
    private final MarketOrderDeliveryFeeRepository deliveryFeeRepository;

    /** Single-order assembly. */
    public OrderResponse toResponse(MarketOrder order) {
        List<MarketOrderItem> items = itemRepository.findByOrderId(order.getId());
        List<OrderFulfilment> parcels = fulfilmentService.forOrder(order.getId());
        List<OrderResponse.Seller> sellers = sellersOf(order, items,
                orderSellerRepository.findByOrderId(order.getId()),
                deliveryFeeRepository.findByOrderId(order.getId()));
        return build(order, items, parcels, sellerNames(parcels), disputes(parcels),
                settlements(parcels), sellers,
                collectionPointsOf(Map.of(order.getId(), sellers), parcels)
                        .getOrDefault(order.getId(), Map.of()));
    }

    /**
     * Assembly for an order the caller already holds the lines, seller rows and
     * fee rows of — the create path, which has just written them and must not
     * read them back. Rendered through the same {@link #sellersOf} as every
     * read, so the order a buyer is handed at creation says exactly what
     * reading it back says.
     */
    public OrderResponse toResponse(MarketOrder order, List<MarketOrderItem> items,
                                    List<MarketOrderSeller> sellerRows,
                                    List<MarketOrderDeliveryFee> feeRows) {
        List<OrderResponse.Seller> sellers = sellersOf(order, items, sellerRows, feeRows);
        return build(order, items, List.of(), Map.of(), Map.of(), Map.of(), sellers,
                collectionPointsOf(Map.of(order.getId(), sellers), List.of())
                        .getOrDefault(order.getId(), Map.of()));
    }

    /** Page assembly: a fixed number of extra queries for the whole page, never per row. */
    public Page<OrderResponse> toResponsePage(Page<MarketOrder> page) {
        List<UUID> orderIds = page.getContent().stream().map(MarketOrder::getId).toList();
        Map<UUID, List<MarketOrderItem>> itemsByOrder = orderIds.isEmpty() ? Map.of()
                : itemRepository.findByOrderIdIn(orderIds).stream()
                        .collect(Collectors.groupingBy(MarketOrderItem::getOrderId));
        // Each seller's method and fee: one query per table for the page.
        Map<UUID, List<MarketOrderSeller>> sellerRowsByOrder = orderIds.isEmpty() ? Map.of()
                : orderSellerRepository.findByOrderIdIn(orderIds).stream()
                        .collect(Collectors.groupingBy(MarketOrderSeller::getOrderId));
        Map<UUID, List<MarketOrderDeliveryFee>> feeRowsByOrder = orderIds.isEmpty() ? Map.of()
                : deliveryFeeRepository.findByOrderIdIn(orderIds).stream()
                        .collect(Collectors.groupingBy(MarketOrderDeliveryFee::getOrderId));
        Map<UUID, List<OrderResponse.Seller>> sellersByOrder = new LinkedHashMap<>();
        for (MarketOrder order : page.getContent()) {
            sellersByOrder.put(order.getId(), sellersOf(order,
                    itemsByOrder.getOrDefault(order.getId(), List.of()),
                    sellerRowsByOrder.getOrDefault(order.getId(), List.of()),
                    feeRowsByOrder.getOrDefault(order.getId(), List.of())));
        }
        Map<UUID, List<OrderFulfilment>> parcelsByOrder = fulfilmentService.forOrders(orderIds);
        List<OrderFulfilment> allParcels = parcelsByOrder.values().stream()
                .flatMap(List::stream).toList();
        Map<UUID, String> sellers = sellerNames(allParcels);
        Map<UUID, DisputeResponse> disputes = disputes(allParcels);
        Map<UUID, MerchantSettlement> settlements = settlements(allParcels);
        Map<UUID, Map<UUID, CollectionPointResponse>> points =
                collectionPointsOf(sellersByOrder, allParcels);
        return page.map(order -> build(order,
                itemsByOrder.getOrDefault(order.getId(), List.of()),
                parcelsByOrder.getOrDefault(order.getId(), List.of()),
                sellers, disputes, settlements, sellersByOrder.get(order.getId()),
                points.getOrDefault(order.getId(), Map.of())));
    }

    /**
     * Every seller on the order, in basket order (the lines' own order), with
     * the method recorded for them at order time ({@code market_order_seller})
     * and, when they deliver, their fee ({@code market_order_delivery_fee},
     * absent on an order placed before fees were per seller).
     *
     * <p>Every order has a seller row per seller - written with the order since
     * V20 and backfilled by V21 - so the fallback to the order's summary is a
     * guard, not a path: it is exactly what a pre-V20 order meant, when one
     * method applied to every seller, and a read must never fail an order over
     * a row it can render without.
     */
    private static List<OrderResponse.Seller> sellersOf(MarketOrder order,
                                                        List<MarketOrderItem> items,
                                                        List<MarketOrderSeller> sellerRows,
                                                        List<MarketOrderDeliveryFee> feeRows) {
        Map<UUID, DeliveryMethod> methodBySeller = new LinkedHashMap<>();
        for (MarketOrderSeller row : sellerRows) {
            methodBySeller.put(row.getMerchantId(), row.getDeliveryMethod());
        }
        Map<UUID, Long> feeBySeller = new HashMap<>();
        for (MarketOrderDeliveryFee row : feeRows) {
            feeBySeller.put(row.getMerchantId(), row.getFeeCents());
        }
        Set<UUID> merchants = new LinkedHashSet<>();
        items.stream().map(MarketOrderItem::getMerchantId).filter(Objects::nonNull)
                .forEach(merchants::add);
        merchants.addAll(methodBySeller.keySet());
        List<OrderResponse.Seller> out = new ArrayList<>(merchants.size());
        for (UUID merchantId : merchants) {
            DeliveryMethod method = methodBySeller.getOrDefault(merchantId,
                    order.getDeliverySummary());
            out.add(new OrderResponse.Seller(merchantId, method,
                    method == DeliveryMethod.DELIVERY ? feeBySeller.get(merchantId) : null));
        }
        return out;
    }

    /** The sellers of {@code sellers} whose goods are collected, in basket order. */
    private static List<UUID> collectingSellers(List<OrderResponse.Seller> sellers) {
        return sellers.stream()
                .filter(seller -> seller.deliveryMethod() == DeliveryMethod.COLLECTION)
                .map(OrderResponse.Seller::merchantId)
                .toList();
    }

    /**
     * ONE snapshot query (plus one for live hours) for every order on the page
     * that has something collected: an order with a COLLECTING seller - every
     * seller of a COLLECTION order, the collected half of a mixed one, from the
     * moment it is placed - or with a COLLECTION parcel (the PARCEL's method).
     * A page of delivered-only orders never asks.
     */
    private Map<UUID, Map<UUID, CollectionPointResponse>> collectionPointsOf(
            Map<UUID, List<OrderResponse.Seller>> sellersByOrder, List<OrderFulfilment> parcels) {
        Set<UUID> collecting = new LinkedHashSet<>();
        sellersByOrder.forEach((orderId, sellers) -> {
            if (!collectingSellers(sellers).isEmpty()) {
                collecting.add(orderId);
            }
        });
        parcels.stream()
                .filter(p -> p.getDeliveryMethod() == DeliveryMethod.COLLECTION)
                .map(OrderFulfilment::getOrderId)
                .forEach(collecting::add);
        return collectionPoints.snapshotsFor(collecting);
    }

    private OrderResponse build(MarketOrder order, List<MarketOrderItem> items,
                                List<OrderFulfilment> parcels, Map<UUID, String> sellerNames,
                                Map<UUID, DisputeResponse> disputes,
                                Map<UUID, MerchantSettlement> settlements,
                                List<OrderResponse.Seller> sellers,
                                Map<UUID, CollectionPointResponse> pointBySeller) {
        List<OrderResponse.Line> lines = items.stream().map(OrderViewAssembler::toLine).toList();
        // Where the COLLECTING sellers' goods are collected: every seller on a
        // COLLECTION order (the pre-V20 list, byte for byte), only the
        // collecting ones on a mixed order, absent when nobody collects.
        List<UUID> collecting = collectingSellers(sellers);
        return new OrderResponse(
                order.getId(),
                order.getOrderRef(),
                order.getStatus(),
                order.getSubtotalCents(),
                order.getDeliveryFeeCents(),
                order.getTotalCents(),
                order.getCurrency(),
                order.getDeliverySummary(),
                // The buyer's copy names the address-book entry it came from.
                FulfilmentDestination.forBuyer(order),
                order.getExpiresAt(),
                order.getCreatedAt(),
                order.getPaidAt(),
                lines,
                // Only while there is something to pay. On a paid, cancelled or
                // expired order a "how to pay" block is an invitation to try.
                order.getStatus() == OrderStatus.PENDING_PAYMENT
                        ? checkoutService.paymentInstruction(order.getOrderRef(),
                                order.getTotalCents(), order.getCurrency(), order.getExpiresAt())
                        : null,
                FulfilmentService.rollUp(parcels),
                toParcels(order, parcels, items, sellerNames, disputes, settlements,
                        pointBySeller),
                toRecipient(order),
                collecting.isEmpty() ? null
                        : CollectionPointViews.perSeller(collecting, pointBySeller),
                // The same rule the cancel endpoint enforces: the order state machine.
                new OrderActions(OrderStateMachine.isLegal(order.getStatus(), OrderStatus.CANCELLED)),
                sellers);
    }

    /** Present only when the order was bought for someone else — the block's
     *  absence is how every surface knows this is not a gift. */
    private static OrderResponse.Recipient toRecipient(MarketOrder order) {
        if (order.getRecipientName() == null) {
            return null;
        }
        return new OrderResponse.Recipient(order.getRecipientName(),
                order.getRecipientMsisdn(), order.getGiftMessage());
    }

    /** Each parcel carries only ITS seller's lines, so the buyer can see which
     *  of their items are coming from where. */
    private List<FulfilmentResponse> toParcels(MarketOrder order,
                                               List<OrderFulfilment> parcels,
                                               List<MarketOrderItem> items,
                                               Map<UUID, String> sellerNames,
                                               Map<UUID, DisputeResponse> disputes,
                                               Map<UUID, MerchantSettlement> settlements,
                                               Map<UUID, CollectionPointResponse> points) {
        List<FulfilmentResponse> out = new ArrayList<>(parcels.size());
        Instant now = Instant.now();
        for (OrderFulfilment parcel : parcels) {
            // Actions and deadlines from the SAME rules the buyer endpoints
            // enforce — see BuyerParcelRules. Every parcel-level fact reads the
            // PARCEL's method, never the order's summary.
            boolean collected = parcel.getDeliveryMethod() == DeliveryMethod.COLLECTION;
            BuyerParcelRules.BuyerParcelState state = buyerRules.stateOf(order.getStatus(),
                    parcel, settlements.get(parcel.getId()),
                    disputes.containsKey(parcel.getId()), now);
            out.add(new FulfilmentResponse(
                    parcel.getId(),
                    parcel.getMerchantId(),
                    sellerNames.get(parcel.getMerchantId()),
                    parcel.getStatus(),
                    parcel.getDispatchNote(),
                    parcel.getDispatchedAt(),
                    parcel.getDeliveredAt(),
                    parcel.getDeliveredBy(),
                    items.stream()
                            .filter(item -> parcel.getMerchantId().equals(item.getMerchantId()))
                            .map(OrderViewAssembler::toLine)
                            .toList(),
                    disputes.get(parcel.getId()),
                    parcel.getCollectCodeIssuedAt(),
                    parcel.getCollectCodeRedeemedAt(),
                    parcel.getUnfulfilledReason(),
                    parcel.getUnfulfilledAt(),
                    parcel.getUnfulfilledBy(),
                    parcel.getTrackingCode(),
                    TrackingStatus.of(parcel.getStatus()),
                    parcel.getDeliveryFeeCents(),
                    collected ? points.get(parcel.getMerchantId()) : null,
                    state.actions(),
                    state.receivedAt(),
                    state.closedAt(),
                    state.closedBy(),
                    state.disputableUntil(),
                    state.paymentReleasesAt(),
                    parcel.getDeliveryMethod()));
        }
        return out;
    }

    /** ONE settlement lookup for every parcel across the whole page — what the
     *  cancel and dispute rules (and the payment-release clock) read. */
    private Map<UUID, MerchantSettlement> settlements(List<OrderFulfilment> parcels) {
        if (parcels.isEmpty()) {
            return Map.of();
        }
        return settlementRepository.findByFulfilmentIdIn(
                        parcels.stream().map(OrderFulfilment::getId).toList())
                .stream()
                .collect(Collectors.toMap(MerchantSettlement::getFulfilmentId, st -> st));
    }

    /** ONE dispute lookup for every parcel across the whole page — the buyer's
     *  tracking screen must say when their complaint is with the operators. */
    private Map<UUID, DisputeResponse> disputes(List<OrderFulfilment> parcels) {
        if (parcels.isEmpty()) {
            return Map.of();
        }
        return disputeRepository.findByFulfilmentIdIn(
                        parcels.stream().map(OrderFulfilment::getId).toList())
                .stream()
                .collect(Collectors.toMap(SettlementDispute::getFulfilmentId,
                        DisputeResponse::from));
    }

    /** ONE lookup for every seller across the whole page. */
    private Map<UUID, String> sellerNames(List<OrderFulfilment> parcels) {
        List<UUID> merchantIds = parcels.stream()
                .map(OrderFulfilment::getMerchantId).distinct().toList();
        if (merchantIds.isEmpty()) {
            return Map.of();
        }
        // Operator-set name wins; the organization registry fills the gaps, so a
        // buyer's order no longer names an unapproved seller by UUID. Still
        // one batch for the page, and still best-effort: an unreachable
        // registry leaves the name absent exactly as before.
        Map<UUID, MarketplaceSeller> sellers = sellerService.findAllByMerchantIds(merchantIds);
        return sellerService.displayNames(merchantIds, sellers);
    }

    static OrderResponse.Line toLine(MarketOrderItem item) {
        return OrderResponse.Line.of(item);
    }
}
