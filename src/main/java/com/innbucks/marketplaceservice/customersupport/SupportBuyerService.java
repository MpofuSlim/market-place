package com.innbucks.marketplaceservice.customersupport;

import com.innbucks.marketplaceservice.cart.CartItemRepository;
import com.innbucks.marketplaceservice.cart.CartVariantItemRepository;
import com.innbucks.marketplaceservice.customersupport.dto.SupportBuyerResponse;
import com.innbucks.marketplaceservice.delivery.DeliveryAddressRepository;
import com.innbucks.marketplaceservice.delivery.dto.AddressResponse;
import com.innbucks.marketplaceservice.favorite.ListingFavoriteRepository;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.fulfilment.MerchantParcelViewAssembler;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.order.OrderService;
import com.innbucks.marketplaceservice.order.OrderViewAssembler;
import com.innbucks.marketplaceservice.order.dto.OrderPageResponse;
import com.innbucks.marketplaceservice.report.ListingReportRepository;
import com.innbucks.marketplaceservice.review.ListingReviewRepository;
import com.innbucks.marketplaceservice.seller.NameResolvingRead;
import com.innbucks.marketplaceservice.settlement.MerchantSettlement;
import com.innbucks.marketplaceservice.settlement.MerchantSettlementRepository;
import com.innbucks.marketplaceservice.settlement.SettlementDispute;
import com.innbucks.marketplaceservice.settlement.SettlementDisputeRepository;
import com.innbucks.marketplaceservice.settlement.SettlementStatus;
import com.innbucks.marketplaceservice.settlement.SettlementViewAssembler;
import com.innbucks.marketplaceservice.settlement.dto.DisputeResponse;
import com.innbucks.marketplaceservice.customersupport.messaging.SupportMessageHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The buyer 360 and the buyer's order history, for the call center.
 *
 * <p>Reads are {@link NameResolvingRead} — NOT transactional — because the
 * order views carry seller names from the organization registry. The subject
 * is proven to exist first (a 404 is logged nowhere), then the view is logged,
 * then it is rendered.
 */
@Service
@RequiredArgsConstructor
public class SupportBuyerService {

    static final int RECENT_ORDERS = 5;
    static final int LIST_LIMIT = 20;
    static final int MAX_ORDER_PAGE_SIZE = 50;
    static final int RECENT_NOTES = 3;
    static final int RECENT_MESSAGES = 3;

    private final SupportSubjects subjects;
    private final SupportActivityLog activityLog;
    private final SupportNoteService notes;
    private final SupportMessageHistory messages;
    private final MarketOrderRepository orderRepository;
    private final OrderViewAssembler orderViews;
    private final OrderService orderService;
    private final OrderFulfilmentRepository fulfilmentRepository;
    private final MerchantParcelViewAssembler parcelViews;
    private final SettlementDisputeRepository disputeRepository;
    private final MerchantSettlementRepository settlementRepository;
    private final SettlementViewAssembler settlementViews;
    private final DeliveryAddressRepository addressRepository;
    private final CartItemRepository cartItemRepository;
    private final CartVariantItemRepository cartVariantItemRepository;
    private final ListingFavoriteRepository favoriteRepository;
    private final ListingReviewRepository reviewRepository;
    private final ListingReportRepository reportRepository;

    @NameResolvingRead
    public SupportBuyerResponse profile(SupportAgent agent, UUID buyerUuid) {
        subjects.requireBuyer(buyerUuid);
        activityLog.record(agent, SupportActions.VIEW_BUYER, SubjectKind.BUYER, buyerUuid, null);

        List<SupportBuyerResponse.Phone> phones = orderRepository.phonesOf(buyerUuid).stream()
                .map(p -> new SupportBuyerResponse.Phone(p.getMsisdn(), p.getOrders(), p.getLastUsedAt()))
                .toList();

        List<MarketOrderRepository.StatusTotal> totals = orderRepository.totalsByStatus(buyerUuid);
        Page<MarketOrder> recent = orderRepository.findByBuyerUuid(buyerUuid,
                PageRequest.of(0, RECENT_ORDERS, newestFirst()));
        Instant lastOrderAt = recent.getContent().stream().map(MarketOrder::getCreatedAt).findFirst().orElse(null);
        SupportBuyerResponse.OrderTotals orderTotals = new SupportBuyerResponse.OrderTotals(
                totals.stream().mapToLong(MarketOrderRepository.StatusTotal::getOrders).sum(),
                lastOrderAt,
                totals.stream()
                        .sorted(Comparator.comparing(t -> t.getStatus().name()))
                        .map(t -> new SupportBuyerResponse.StatusTotal(t.getStatus(), t.getOrders(),
                                t.getTotalCents()))
                        .toList());

        List<SettlementDispute> disputes = disputeRepository.findByBuyerUuidOrderByCreatedAtDesc(buyerUuid,
                PageRequest.of(0, LIST_LIMIT));
        Map<UUID, MerchantSettlement> disputedMoney = settlementRepository
                .findAllById(disputes.stream().map(SettlementDispute::getSettlementId).distinct().toList())
                .stream().collect(Collectors.toMap(MerchantSettlement::getId, Function.identity()));

        SupportBuyerResponse.Engagement engagement = new SupportBuyerResponse.Engagement(
                cartItemRepository.countByBuyerUuid(buyerUuid) + cartVariantItemRepository.countByBuyerUuid(buyerUuid),
                favoriteRepository.countByIdBuyerUuid(buyerUuid),
                reviewRepository.countByBuyerUuid(buyerUuid),
                reportRepository.countByReporterUuid(buyerUuid));

        return new SupportBuyerResponse(
                buyerUuid,
                phones,
                orderTotals,
                // Rendered last among the order reads: this is where seller
                // names are looked up, with no connection held.
                orderViews.toResponsePage(recent).getContent(),
                parcelViews.toViews(fulfilmentRepository.findForBuyer(buyerUuid,
                        List.of(FulfilmentStatus.PREPARING, FulfilmentStatus.DISPATCHED),
                        PageRequest.of(0, LIST_LIMIT))),
                disputes.stream().map(d -> DisputeResponse.from(d, disputedMoney.get(d.getSettlementId()))).toList(),
                settlementViews.toResponses(settlementRepository.findForBuyer(buyerUuid,
                        List.of(SettlementStatus.REFUND_DUE, SettlementStatus.REFUNDED),
                        PageRequest.of(0, LIST_LIMIT))),
                addressRepository.findByBuyerUuidOrderByDefaultAddressDescCreatedAtDesc(buyerUuid).stream()
                        .map(AddressResponse::from).toList(),
                engagement,
                notes.recent(SubjectKind.BUYER, buyerUuid, RECENT_NOTES),
                messages.recent(SubjectKind.BUYER, buyerUuid, RECENT_MESSAGES));
    }

    @NameResolvingRead
    public OrderPageResponse orders(SupportAgent agent, UUID buyerUuid, int page, int size) {
        subjects.requireBuyer(buyerUuid);
        int safePage = Math.max(page, 0);
        activityLog.record(agent, SupportActions.VIEW_BUYER_ORDERS, SubjectKind.BUYER, buyerUuid,
                Map.of("page", safePage));
        return OrderPageResponse.from(orderService.getAll(buyerUuid,
                PageRequest.of(safePage, Math.clamp(size, 1, MAX_ORDER_PAGE_SIZE), newestFirst())));
    }

    private static Sort newestFirst() {
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    }
}
