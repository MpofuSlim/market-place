package com.innbucks.marketplaceservice.pickup;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.pickup.dto.CollectionPointChoice;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where each seller's goods on a COLLECTION basket are collected — ONE
 * resolver shared by the checkout quote and order creation, so the point a
 * buyer was quoted is the point their order records (the CheckoutPricer
 * reason, one level over).
 *
 * <p>Per seller: the buyer's explicit choice when they made one; otherwise the
 * seller's default point; otherwise none, and collection is arranged with the
 * seller directly — exactly what every collection meant before points
 * existed, so a seller who has not set one up never blocks a sale.
 */
@Component
@RequiredArgsConstructor
public class CollectionPointResolver {

    private final CollectionPointRepository points;
    private final OrderCollectionPointRepository snapshots;

    /**
     * @param merchantIds the sellers in the basket
     * @param choices     the buyer's choices; entries for a seller NOT in the
     *                    basket are ignored (the basket may have changed since
     *                    the screen was drawn)
     * @return seller → point, for every seller that has one
     * @throws ApiException 400 {@code unknown_collection_point} for a choice
     *         that is not one of that seller's points; 400
     *         {@code duplicate_collection_point_choice} for two choices for one
     *         seller
     */
    @Transactional(readOnly = true)
    public Map<UUID, CollectionPoint> resolve(Collection<UUID> merchantIds,
                                              List<CollectionPointChoice> choices) {
        Set<UUID> sellers = new LinkedHashSet<>(merchantIds);
        if (sellers.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<CollectionPoint>> bySeller = new HashMap<>();
        for (CollectionPoint p : points.findForMerchants(sellers)) {
            bySeller.computeIfAbsent(p.getMerchantId(), m -> new ArrayList<>()).add(p);
        }
        Map<UUID, UUID> chosen = new HashMap<>();
        Set<UUID> seen = new HashSet<>();
        for (CollectionPointChoice choice : choices == null ? List.<CollectionPointChoice>of() : choices) {
            if (choice == null || choice.merchantId() == null || choice.collectionPointId() == null) {
                continue;
            }
            if (!seen.add(choice.merchantId())) {
                throw ApiException.badRequest("duplicate_collection_point_choice",
                        "collectionPoints names the same seller more than once");
            }
            if (sellers.contains(choice.merchantId())) {
                chosen.put(choice.merchantId(), choice.collectionPointId());
            }
        }
        Map<UUID, CollectionPoint> out = new LinkedHashMap<>();
        for (UUID seller : sellers) {
            List<CollectionPoint> options = bySeller.getOrDefault(seller, List.of());
            UUID wanted = chosen.get(seller);
            if (wanted != null) {
                // data names the seller and the stale choice, so a client with
                // several sellers in the basket re-asks for the right one.
                CollectionPoint match = options.stream()
                        .filter(p -> p.getId().equals(wanted)).findFirst()
                        .orElseThrow(() -> ApiException.badRequest("unknown_collection_point",
                                "That collection point is not one of this seller's - choose "
                                        + "again from their collection points")
                                .withDetails(Map.of("merchantId", seller.toString(),
                                        "collectionPointId", wanted.toString())));
                out.put(seller, match);
            } else if (!options.isEmpty()) {
                // Ordered default first (findForMerchants), so the head IS the default.
                out.put(seller, options.getFirst());
            }
        }
        return out;
    }

    /**
     * Copies each resolved point onto the order: the V18 snapshot. Joins the
     * caller's transaction — it is written with the order or not at all.
     */
    @Transactional
    public void record(UUID orderId, Map<UUID, CollectionPoint> resolved) {
        if (resolved == null || resolved.isEmpty()) {
            return;
        }
        snapshots.saveAll(resolved.values().stream()
                .map(point -> OrderCollectionPoint.of(orderId, point))
                .toList());
    }
}
