package com.innbucks.marketplaceservice.customersupport;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.customersupport.dto.SupportOrderResponse;
import com.innbucks.marketplaceservice.fulfilment.MerchantParcelViewAssembler;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderEventRepository;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.order.OrderViewAssembler;
import com.innbucks.marketplaceservice.seller.NameResolvingRead;
import com.innbucks.marketplaceservice.seller.SellerService;
import com.innbucks.marketplaceservice.settlement.MerchantSettlementRepository;
import com.innbucks.marketplaceservice.settlement.SettlementViewAssembler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One order as the call center sees it: the buyer's own view (so an agent can
 * say exactly what the customer is looking at), each seller's parcel card
 * (tracking, code state, the last notice and whether it reached the buyer),
 * the money, and the order's full journal.
 *
 * <p>{@link NameResolvingRead}, not transactional — the views carry seller names.
 */
@Service
@RequiredArgsConstructor
public class SupportOrderService {

    static final int RECENT_NOTES = 5;

    private final SupportSubjects subjects;
    private final SupportActivityLog activityLog;
    private final SupportNoteService notes;
    private final MarketOrderRepository orderRepository;
    private final MarketOrderItemRepository orderItemRepository;
    private final MarketOrderEventRepository eventRepository;
    private final OrderViewAssembler orderViews;
    private final OrderFulfilmentRepository fulfilmentRepository;
    private final MerchantParcelViewAssembler parcelViews;
    private final MerchantSettlementRepository settlementRepository;
    private final SettlementViewAssembler settlementViews;
    private final SellerService sellerService;

    /**
     * @param orderKey an order id, or its reference ({@code MKT-...}, case and
     *                 dashes forgiven the way the search box forgives them)
     */
    @NameResolvingRead
    public SupportOrderResponse detail(SupportAgent agent, String orderKey) {
        MarketOrder order = resolve(orderKey);
        activityLog.record(agent, SupportActions.VIEW_ORDER, SubjectKind.ORDER, order.getId(),
                Map.of("orderRef", order.getOrderRef()));

        List<OrderFulfilment> parcels = fulfilmentRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        List<UUID> sellerIds = orderItemRepository.findByOrderId(order.getId()).stream()
                .map(MarketOrderItem::getMerchantId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<SupportOrderResponse.TimelineEntry> timeline = eventRepository.findByOrderIdOrderByIdAsc(order.getId())
                .stream()
                .map(e -> new SupportOrderResponse.TimelineEntry(e.getCreatedAt(), e.getKind(), e.getFromStatus(),
                        e.getToStatus(), e.getDetail()))
                .toList();

        // Everything that looks names up comes last, with no connection held.
        Map<UUID, String> names = sellerIds.isEmpty() ? Map.of() : sellerService.displayNames(sellerIds);
        return new SupportOrderResponse(
                orderViews.toResponse(order),
                new SupportOrderResponse.Buyer(order.getBuyerUuid(), order.getBuyerMsisdn()),
                sellerIds.stream().map(id -> new SupportOrderResponse.Seller(id, names.get(id))).toList(),
                parcelViews.toViews(parcels),
                parcels.isEmpty() ? List.of() : settlementViews.toResponses(settlementRepository
                        .findByFulfilmentIdIn(parcels.stream().map(OrderFulfilment::getId).toList())),
                timeline,
                notes.recent(SubjectKind.ORDER, order.getId(), RECENT_NOTES));
    }

    private MarketOrder resolve(String orderKey) {
        String key = orderKey == null ? "" : orderKey.trim();
        if (key.toUpperCase(Locale.ROOT).startsWith("MKT")) {
            String ref = "MKT-" + key.toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "").substring(3);
            return orderRepository.findByOrderRef(ref)
                    .orElseThrow(() -> ApiException.notFound("order_not_found", "Order not found"));
        }
        UUID id;
        try {
            id = UUID.fromString(key);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("invalid_order_key",
                    "Name the order by its id or its reference (MKT-...)");
        }
        return subjects.requireOrder(id);
    }
}
