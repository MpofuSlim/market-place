package com.innbucks.marketplaceservice.pickup;

import com.innbucks.marketplaceservice.config.MarketZone;
import com.innbucks.marketplaceservice.delivery.DeliveryTownCatalog;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointResponse;
import com.innbucks.marketplaceservice.pickup.dto.CollectionTown;
import com.innbucks.marketplaceservice.pickup.dto.SellerCollectionPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Every read of collection points, assembled in BATCHES: a catalogue page, an
 * orders page or a parcel queue costs one query per table, never one per row
 * (the ListingViewAssembler discipline).
 *
 * <p>Two kinds of point come out of here and they must not be confused: a
 * seller's LIVE points (portal, public profile, catalogue towns) and an
 * order's SNAPSHOT (buyer order, parcel, tracking, collection code). A
 * snapshot's address is what was copied at order time; only its hours are
 * read live, through the point it came from, while that point still exists.
 */
@Component
@RequiredArgsConstructor
public class CollectionPointViews {

    private final CollectionPointRepository points;
    private final CollectionPointHoursRepository hours;
    private final OrderCollectionPointRepository snapshots;
    private final DeliveryTownCatalog towns;
    private final MarketZone marketZone;

    // ------------------------------------------------------------------
    // Live points
    // ------------------------------------------------------------------

    /** One seller's points, default first. Empty — never an error — for a
     *  merchant with none or one that does not exist (no existence oracle). */
    @Transactional(readOnly = true)
    public List<CollectionPointResponse> forMerchant(UUID merchantId) {
        return render(points.findForMerchant(merchantId));
    }

    /** One point of one seller, or null — owner-scoped. */
    @Transactional(readOnly = true)
    public CollectionPointResponse one(UUID merchantId, UUID pointId) {
        return points.findByIdAndMerchantId(pointId, merchantId)
                .map(p -> render(List.of(p)).getFirst())
                .orElse(null);
    }

    /**
     * The towns each seller can be collected in, for catalogue cards: ONE
     * query for a whole page. Distinct per seller, in the towns' display order;
     * a seller with no point maps to an empty list.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<CollectionTown>> townsFor(Collection<UUID> merchantIds) {
        if (merchantIds == null || merchantIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<String>> codes = new HashMap<>();
        for (CollectionPoint p : points.findForMerchants(new LinkedHashSet<>(merchantIds))) {
            codes.computeIfAbsent(p.getMerchantId(), m -> new LinkedHashSet<>()).add(p.getTownCode());
        }
        Map<UUID, List<CollectionTown>> out = new HashMap<>();
        codes.forEach((merchantId, set) -> out.put(merchantId, towns.all().stream()
                .filter(t -> set.contains(t.getCode()))
                .map(t -> new CollectionTown(t.getCode(), t.getName()))
                .toList()));
        return out;
    }

    // ------------------------------------------------------------------
    // Order snapshots
    // ------------------------------------------------------------------

    /**
     * Every snapshot for a batch of orders: order id → seller id → point. One
     * query for the snapshots, one for the live hours of the points they came
     * from.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Map<UUID, CollectionPointResponse>> snapshotsFor(Collection<UUID> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return Map.of();
        }
        List<OrderCollectionPoint> rows = snapshots.findByOrderIdIn(new LinkedHashSet<>(orderIds));
        if (rows.isEmpty()) {
            return Map.of();
        }
        Set<UUID> sources = rows.stream()
                .map(OrderCollectionPoint::getCollectionPointId).collect(Collectors.toSet());
        Map<UUID, CollectionPoint> live = points.findAllById(sources).stream()
                .collect(Collectors.toMap(CollectionPoint::getId, p -> p));
        Map<UUID, List<CollectionPointHours>> liveHours = hoursByPoint(live.keySet());
        ZonedDateTime now = ZonedDateTime.now(marketZone.zone());
        Map<UUID, Map<UUID, CollectionPointResponse>> out = new HashMap<>();
        for (OrderCollectionPoint row : rows) {
            CollectionPoint source = live.get(row.getCollectionPointId());
            out.computeIfAbsent(row.getOrderId(), o -> new HashMap<>())
                    .put(row.getMerchantId(), snapshot(row, source,
                            liveHours.get(row.getCollectionPointId()), now));
        }
        return out;
    }

    /** One order's snapshot for one seller, or null. */
    @Transactional(readOnly = true)
    public CollectionPointResponse snapshotFor(UUID orderId, UUID merchantId) {
        return snapshotsFor(List.of(orderId)).getOrDefault(orderId, Map.of()).get(merchantId);
    }

    /**
     * An order's sellers with where each is collected, in the given seller
     * order; a seller without a snapshot is listed with no point ("arrange with
     * the seller"). Empty for a DELIVERY order — the caller decides.
     */
    public static List<SellerCollectionPoint> perSeller(Collection<UUID> merchantIds,
                                                        Map<UUID, CollectionPointResponse> bySeller) {
        List<SellerCollectionPoint> out = new ArrayList<>();
        for (UUID merchantId : new LinkedHashSet<>(merchantIds)) {
            out.add(new SellerCollectionPoint(merchantId,
                    bySeller == null ? null : bySeller.get(merchantId)));
        }
        return out;
    }

    // ------------------------------------------------------------------

    /** Live points with their hours, in the order given. */
    public List<CollectionPointResponse> render(List<CollectionPoint> live) {
        if (live.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<CollectionPointHours>> byPoint = hoursByPoint(
                live.stream().map(CollectionPoint::getId).collect(Collectors.toSet()));
        ZonedDateTime now = ZonedDateTime.now(marketZone.zone());
        List<CollectionPointResponse> out = new ArrayList<>(live.size());
        for (CollectionPoint p : live) {
            List<CollectionPointHours> h = byPoint.getOrDefault(p.getId(), List.of());
            out.add(new CollectionPointResponse(p.getId(), p.getName(), p.getTownCode(),
                    towns.nameOf(p.getTownCode()), p.getLine1(), p.getLine2(), p.getArea(),
                    p.getLandmark(), p.getPhone(), p.getHoursNote(), p.getLatitude(),
                    p.getLongitude(), h.isEmpty() ? null : OpeningHours.entries(h),
                    OpeningHours.summary(h), OpeningHours.openNow(h, now), p.isDefaultPoint(),
                    p.getUpdatedAt()));
        }
        return out;
    }

    /** The copied address, with the source point's CURRENT hours and hours
     *  note while it still exists. */
    private CollectionPointResponse snapshot(OrderCollectionPoint row, CollectionPoint source,
                                             List<CollectionPointHours> h, ZonedDateTime now) {
        boolean hasHours = h != null && !h.isEmpty();
        return new CollectionPointResponse(row.getCollectionPointId(), row.getName(),
                row.getTownCode(), towns.nameOf(row.getTownCode()), row.getLine1(),
                row.getLine2(), row.getArea(), row.getLandmark(), row.getPhone(),
                source == null ? null : source.getHoursNote(),
                row.getLatitude(), row.getLongitude(),
                hasHours ? OpeningHours.entries(h) : null,
                hasHours ? OpeningHours.summary(h) : null,
                hasHours ? OpeningHours.openNow(h, now) : null,
                null, null);
    }

    private Map<UUID, List<CollectionPointHours>> hoursByPoint(Set<UUID> pointIds) {
        if (pointIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<CollectionPointHours>> out = new LinkedHashMap<>();
        for (CollectionPointHours h : hours.findForPoints(pointIds)) {
            out.computeIfAbsent(h.getPointId(), id -> new ArrayList<>()).add(h);
        }
        return out;
    }
}
