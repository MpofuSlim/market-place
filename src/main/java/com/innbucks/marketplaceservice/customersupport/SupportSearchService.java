package com.innbucks.marketplaceservice.customersupport;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.api.Msisdns;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.customersupport.dto.SupportSearchResponse;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.fulfilment.tracking.TrackingCodes;
import com.innbucks.marketplaceservice.notify.MsisdnMasking;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.seller.MarketplaceSeller;
import com.innbucks.marketplaceservice.seller.MarketplaceSellerRepository;
import com.innbucks.marketplaceservice.seller.NameResolvingRead;
import com.innbucks.marketplaceservice.seller.SellerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The support search box: one query, read by SHAPE — an order ref, a tracking
 * code, an id, a phone number or a name — answered with every buyer, order and
 * seller it matches.
 *
 * <p>Deliberately NOT {@code @Transactional} ({@link NameResolvingRead}): the
 * seller hits carry names, which come from the organization registry over HTTP,
 * and that call must never hold a pooled connection. Each query commits on its
 * own; the activity row is written in its own short transaction before any name
 * is looked up.
 *
 * <p>Every search is logged ({@link SupportActions#SEARCH}), including one that
 * finds nothing — "who went looking for this number" is exactly what a
 * supervisor needs to answer. A phone is logged masked, a name not at all.
 */
@Service
@RequiredArgsConstructor
public class SupportSearchService {

    static final int MAX_QUERY_LENGTH = 80;
    static final int LIMIT = 20;
    private static final Pattern PHONE_SHAPE = Pattern.compile("^[+0-9 ()\\-]{7,20}$");

    private final MarketOrderRepository orderRepository;
    private final MarketOrderItemRepository orderItemRepository;
    private final OrderFulfilmentRepository fulfilmentRepository;
    private final MarketplaceSellerRepository sellerRepository;
    private final ListingRepository listingRepository;
    private final SellerService sellerService;
    private final SupportSubjects subjects;
    private final Msisdns msisdns;
    private final SupportActivityLog activityLog;

    enum QueryKind { ORDER_REF, TRACKING_CODE, ID, PHONE, NAME }

    record Query(QueryKind kind, String value) {

        static Query parse(String raw, Msisdns msisdns) {
            if (raw == null || raw.isBlank()) {
                throw ApiException.badRequest("invalid_search", "Type something to search for");
            }
            String q = raw.trim();
            if (q.length() > MAX_QUERY_LENGTH) {
                throw ApiException.badRequest("invalid_search",
                        "Search for at most " + MAX_QUERY_LENGTH + " characters");
            }
            String upper = q.toUpperCase(Locale.ROOT);
            if (upper.startsWith("MKT")) {
                String compact = upper.replace(" ", "").replace("-", "");
                return new Query(QueryKind.ORDER_REF, "MKT-" + compact.substring(3));
            }
            if (upper.startsWith("TRK")) {
                String code = TrackingCodes.normalize(q);
                if (code == null) {
                    throw ApiException.badRequest("invalid_search",
                            "That is not a tracking code - they look like TRK-7F3K9Q2M4X");
                }
                return new Query(QueryKind.TRACKING_CODE, code);
            }
            UUID id = parseUuid(q);
            if (id != null) {
                return new Query(QueryKind.ID, id.toString());
            }
            if (PHONE_SHAPE.matcher(q).matches()) {
                return new Query(QueryKind.PHONE, msisdns.normalize(q, "query"));
            }
            if (q.length() < 2) {
                throw ApiException.badRequest("invalid_search", "Type at least 2 letters of the name");
            }
            return new Query(QueryKind.NAME, escapeLike(q.toLowerCase(Locale.ROOT)));
        }

        private static UUID parseUuid(String q) {
            if (q.length() != 36) {
                return null;
            }
            try {
                return UUID.fromString(q);
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }

        private static String escapeLike(String value) {
            return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        }
    }

    @NameResolvingRead
    public SupportSearchResponse search(SupportAgent agent, String raw) {
        Query query = Query.parse(raw, msisdns);
        Hits hits = new Hits();
        switch (query.kind()) {
            case ORDER_REF -> orderRepository.findByOrderRef(query.value())
                    .ifPresent(order -> hits.exactOrder(order, "ORDER_REF"));
            case TRACKING_CODE -> fulfilmentRepository.findByTrackingCode(query.value())
                    .flatMap(parcel -> orderRepository.findById(parcel.getOrderId()))
                    .ifPresent(order -> hits.exactOrder(order, "TRACKING_CODE"));
            case ID -> byId(UUID.fromString(query.value()), hits);
            case PHONE -> byPhone(query.value(), hits);
            case NAME -> byName(query.value(), hits);
        }
        sellersOfExactOrders(hits);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("queryKind", query.kind().name());
        switch (query.kind()) {
            case PHONE -> detail.put("query", MsisdnMasking.mask(query.value()));
            case NAME -> { } // a name is never logged
            default -> detail.put("query", query.value());
        }
        detail.put("hits", hits.buyers.size() + hits.orders.size() + hits.sellers.size());
        activityLog.record(agent, SupportActions.SEARCH, null, null, detail);

        return hits.render(query.kind(), orderRepository, sellerRepository, sellerService);
    }

    private void byId(UUID id, Hits hits) {
        orderRepository.findById(id).ifPresent(order -> hits.exactOrder(order, "ORDER_ID"));
        fulfilmentRepository.findById(id)
                .flatMap(parcel -> orderRepository.findById(parcel.getOrderId()))
                .ifPresent(order -> hits.exactOrder(order, "PARCEL_ID"));
        if (subjects.buyerExists(id)) {
            hits.buyer(id, "BUYER_ID");
        }
        if (subjects.sellerExists(id)) {
            hits.seller(id, "SELLER_ID");
        }
        listingRepository.findById(id).ifPresent(listing -> hits.seller(listing.getMerchantId(), "LISTING_ID"));
    }

    private void byPhone(String phone, Hits hits) {
        for (MarketOrder order : orderRepository.findTouchingPhone(phone, PageRequest.of(0, LIMIT))) {
            if (phone.equals(order.getBuyerMsisdn())) {
                hits.order(order, "BUYER_PHONE");
                hits.buyer(order.getBuyerUuid(), "BUYER_PHONE");
            }
            if (phone.equals(order.getRecipientMsisdn())) {
                hits.order(order, "GIFT_RECIPIENT_PHONE");
                hits.buyer(order.getBuyerUuid(), "ORDER_BUYER");
            }
            if (phone.equals(order.getDeliveryRecipientMsisdn())) {
                hits.order(order, "DELIVERY_RECIPIENT_PHONE");
                hits.buyer(order.getBuyerUuid(), "ORDER_BUYER");
            }
        }
        for (MarketplaceSeller seller : sellerRepository.findByPayoutMsisdn(phone)) {
            hits.seller(seller.getMerchantId(), "PAYOUT_PHONE");
        }
    }

    private void byName(String pattern, Hits hits) {
        String like = "%" + pattern + "%";
        for (MarketOrder order : orderRepository.findByRecipientNameLike(like, PageRequest.of(0, LIMIT))) {
            hits.order(order, "RECIPIENT_NAME");
            hits.buyer(order.getBuyerUuid(), "ORDER_BUYER");
        }
        for (MarketplaceSeller seller : sellerRepository.findByDisplayNameLike(like, PageRequest.of(0, LIMIT))) {
            hits.seller(seller.getMerchantId(), "SELLER_NAME");
        }
    }

    /** An order found by an EXACT key (ref, tracking code, id) also names its
     *  sellers — the caller on the line is as often the seller as the buyer.
     *  Not for phone/name matches, which can be many orders deep. */
    private void sellersOfExactOrders(Hits hits) {
        if (hits.exactOrderIds.isEmpty()) {
            return;
        }
        for (MarketOrderItem item : orderItemRepository.findByOrderIdIn(hits.exactOrderIds)) {
            hits.seller(item.getMerchantId(), "ORDER_SELLER");
        }
    }

    /** Accumulates matches in first-seen order, each with every reason it matched. */
    private static final class Hits {
        final Map<UUID, Set<String>> buyers = new LinkedHashMap<>();
        final Map<UUID, MarketOrder> orders = new LinkedHashMap<>();
        final Map<UUID, Set<String>> orderReasons = new LinkedHashMap<>();
        final Map<UUID, Set<String>> sellers = new LinkedHashMap<>();
        final List<UUID> exactOrderIds = new ArrayList<>();

        void exactOrder(MarketOrder order, String reason) {
            order(order, reason);
            buyer(order.getBuyerUuid(), "ORDER_BUYER");
            if (!exactOrderIds.contains(order.getId())) {
                exactOrderIds.add(order.getId());
            }
        }

        void order(MarketOrder order, String reason) {
            if (orders.size() >= LIMIT && !orders.containsKey(order.getId())) {
                return;
            }
            orders.putIfAbsent(order.getId(), order);
            orderReasons.computeIfAbsent(order.getId(), k -> new LinkedHashSet<>()).add(reason);
        }

        void buyer(UUID buyerUuid, String reason) {
            add(buyers, buyerUuid, reason);
        }

        void seller(UUID merchantId, String reason) {
            add(sellers, merchantId, reason);
        }

        private static void add(Map<UUID, Set<String>> into, UUID id, String reason) {
            if (id == null || (into.size() >= LIMIT && !into.containsKey(id))) {
                return;
            }
            into.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(reason);
        }

        SupportSearchResponse render(QueryKind kind, MarketOrderRepository orderRepository,
                                     MarketplaceSellerRepository sellerRepository, SellerService sellerService) {
            Map<UUID, MarketOrderRepository.BuyerOrderStats> stats = new LinkedHashMap<>();
            if (!buyers.isEmpty()) {
                for (MarketOrderRepository.BuyerOrderStats s : orderRepository.statsForBuyers(buyers.keySet())) {
                    stats.put(s.getBuyerUuid(), s);
                }
            }
            List<SupportSearchResponse.BuyerHit> buyerHits = buyers.entrySet().stream()
                    .map(e -> {
                        MarketOrderRepository.BuyerOrderStats s = stats.get(e.getKey());
                        return new SupportSearchResponse.BuyerHit(e.getKey(), s == null ? 0 : s.getOrders(),
                                s == null ? null : s.getLastOrderAt(), List.copyOf(e.getValue()));
                    })
                    .toList();
            List<SupportSearchResponse.OrderHit> orderHits = orders.values().stream()
                    .map(o -> new SupportSearchResponse.OrderHit(o.getId(), o.getOrderRef(), o.getStatus(),
                            o.getBuyerUuid(), o.getTotalCents(), o.getCurrency(), o.getDeliverySummary(),
                            o.getCreatedAt(), List.copyOf(orderReasons.get(o.getId()))))
                    .toList();
            List<UUID> sellerIds = List.copyOf(sellers.keySet());
            Map<UUID, MarketplaceSeller> rows = new LinkedHashMap<>();
            if (!sellerIds.isEmpty()) {
                sellerRepository.findAllById(sellerIds).forEach(s -> rows.put(s.getMerchantId(), s));
            }
            // Names last, outside any transaction (see the class comment).
            Map<UUID, String> names = sellerIds.isEmpty() ? Map.of() : sellerService.displayNames(sellerIds, rows);
            List<SupportSearchResponse.SellerHit> sellerHits = sellerIds.stream()
                    .filter(Objects::nonNull)
                    .map(id -> new SupportSearchResponse.SellerHit(id, names.get(id),
                            rows.containsKey(id) ? rows.get(id).getStatus() : null,
                            List.copyOf(sellers.get(id))))
                    .toList();
            return new SupportSearchResponse(kind.name(), buyerHits, orderHits, sellerHits);
        }
    }
}
