package com.innbucks.marketplaceservice.order;

import com.innbucks.marketplaceservice.checkout.CheckoutService;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentService;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.fulfilment.dto.FulfilmentResponse;
import com.innbucks.marketplaceservice.fulfilment.dto.FulfilmentDestination;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import com.innbucks.marketplaceservice.seller.MarketplaceSeller;
import com.innbucks.marketplaceservice.seller.SellerService;
import com.innbucks.marketplaceservice.settlement.SettlementDispute;
import com.innbucks.marketplaceservice.settlement.SettlementDisputeRepository;
import com.innbucks.marketplaceservice.settlement.dto.DisputeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Builds the buyer-facing {@link OrderResponse} — the ONE place an order turns
 * into its wire shape, so the single read, the paged list and the replayed
 * idempotent create can never render the same order differently.
 *
 * <p>Batches everything a page needs: lines, parcels and seller names each
 * cost ONE query for the whole page regardless of its size. A per-order
 * assembly here would be an N+1 three times over on the my-orders screen, which
 * is the screen a shopper opens most.
 */
@Component
@RequiredArgsConstructor
public class OrderViewAssembler {

    private final MarketOrderItemRepository itemRepository;
    private final FulfilmentService fulfilmentService;
    private final SettlementDisputeRepository disputeRepository;
    private final SellerService sellerService;
    private final CheckoutService checkoutService;

    /** Single-order assembly. */
    public OrderResponse toResponse(MarketOrder order) {
        List<MarketOrderItem> items = itemRepository.findByOrderId(order.getId());
        List<OrderFulfilment> parcels = fulfilmentService.forOrder(order.getId());
        return build(order, items, parcels, sellerNames(parcels), disputes(parcels));
    }

    /**
     * Assembly for an order the caller already holds the lines of — the create
     * path, which has just written them and must not read them back.
     */
    public OrderResponse toResponse(MarketOrder order, List<MarketOrderItem> items) {
        return build(order, items, List.of(), Map.of(), Map.of());
    }

    /** Page assembly: three extra queries for the whole page, never per row. */
    public Page<OrderResponse> toResponsePage(Page<MarketOrder> page) {
        List<UUID> orderIds = page.getContent().stream().map(MarketOrder::getId).toList();
        Map<UUID, List<MarketOrderItem>> itemsByOrder = orderIds.isEmpty() ? Map.of()
                : itemRepository.findByOrderIdIn(orderIds).stream()
                        .collect(Collectors.groupingBy(MarketOrderItem::getOrderId));
        Map<UUID, List<OrderFulfilment>> parcelsByOrder = fulfilmentService.forOrders(orderIds);
        List<OrderFulfilment> allParcels = parcelsByOrder.values().stream()
                .flatMap(List::stream).toList();
        Map<UUID, String> sellers = sellerNames(allParcels);
        Map<UUID, DisputeResponse> disputes = disputes(allParcels);
        return page.map(order -> build(order,
                itemsByOrder.getOrDefault(order.getId(), List.of()),
                parcelsByOrder.getOrDefault(order.getId(), List.of()),
                sellers, disputes));
    }

    private OrderResponse build(MarketOrder order, List<MarketOrderItem> items,
                                List<OrderFulfilment> parcels, Map<UUID, String> sellerNames,
                                Map<UUID, DisputeResponse> disputes) {
        List<OrderResponse.Line> lines = items.stream().map(OrderViewAssembler::toLine).toList();
        return new OrderResponse(
                order.getId(),
                order.getOrderRef(),
                order.getStatus(),
                order.getSubtotalCents(),
                order.getDeliveryFeeCents(),
                order.getTotalCents(),
                order.getCurrency(),
                order.getDeliveryMethod(),
                FulfilmentDestination.from(order),
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
                toParcels(parcels, items, sellerNames, disputes),
                toRecipient(order));
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
    private static List<FulfilmentResponse> toParcels(List<OrderFulfilment> parcels,
                                                      List<MarketOrderItem> items,
                                                      Map<UUID, String> sellerNames,
                                                      Map<UUID, DisputeResponse> disputes) {
        List<FulfilmentResponse> out = new ArrayList<>(parcels.size());
        for (OrderFulfilment parcel : parcels) {
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
                    parcel.getUnfulfilledAt()));
        }
        return out;
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
        Map<UUID, MarketplaceSeller> sellers = sellerService.findAllByMerchantIds(merchantIds);
        Map<UUID, String> names = new java.util.LinkedHashMap<>();
        sellers.forEach((id, seller) -> {
            if (seller.getDisplayName() != null) {
                names.put(id, seller.getDisplayName());
            }
        });
        return names;
    }

    static OrderResponse.Line toLine(MarketOrderItem item) {
        return new OrderResponse.Line(item.getListingId(), item.getTitleSnapshot(),
                item.getUnitPriceCents(), item.getQuantity(), item.getLineTotalCents());
    }
}
