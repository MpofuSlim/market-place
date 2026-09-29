package com.innbucks.marketplaceservice.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketOrderRepository extends JpaRepository<MarketOrder, UUID> {

    /** Owner-scoped lookup — a non-owner sees the same empty result as a
     *  nonexistent id, so the customer surface never confirms whether an
     *  order id exists to someone who doesn't own it. */
    Optional<MarketOrder> findByIdAndBuyerUuid(UUID id, UUID buyerUuid);

    Page<MarketOrder> findByBuyerUuid(UUID buyerUuid, Pageable pageable);

    /** S2S lookup by the payments-facing opaque reference. */
    Optional<MarketOrder> findByOrderRef(String orderRef);

    boolean existsByOrderRef(String orderRef);

    /**
     * The order already committed under a namespaced idempotency key, if any.
     * Backed by the V1 partial unique index {@code uq_order_idempotency_key},
     * so it is one index probe and never more than one row. Order creation
     * asks it before running and after a non-business failure, so a key whose
     * order committed REPLAYS that order instead of re-running into the index.
     */
    Optional<MarketOrder> findByIdempotencyKey(String idempotencyKey);

    /** Expiry-sweep candidates; the sweeper passes a bounded, oldest-first
     *  {@link Pageable} and re-checks each row inside its own transaction. */
    List<MarketOrder> findByStatusAndExpiresAtBefore(OrderStatus status, Instant cutoff, Pageable pageable);

    /**
     * The verified-purchase review gate (V5): ids of the caller's PAID orders
     * whose PARCEL carrying the listing was DELIVERED, oldest first — the FIRST
     * qualifying purchase is stored on the review as its provenance. Callers
     * pass a bounded {@link Pageable} (the service only needs one row).
     *
     * <p>Both statuses are pinned in the query, never parameters:
     * PENDING/CANCELLED/EXPIRED orders must never qualify a reviewer, and nor
     * may a PAID one whose goods never reached the buyer — a parcel still
     * PREPARING or DISPATCHED, or one that ended UNFULFILLED (the seller
     * declined, the buyer cancelled, a collection was never picked up) with the
     * money on its way back. DELIVERED covers every kind of handover evidence:
     * the buyer's own confirmation, a redeemed collection code, and a seller's
     * own "delivered".
     *
     * <p>The parcel is matched on the line's SNAPSHOT {@code merchantId}, not the
     * live listing's: the seller who owed these goods is the one selling at
     * order time, and in a two-seller order only THAT seller's parcel counts —
     * the other half arriving qualifies nothing on this listing.
     */
    @Query("""
            select o.id
              from MarketOrder o
              join MarketOrderItem i on i.orderId = o.id
              join OrderFulfilment f on f.orderId = o.id and f.merchantId = i.merchantId
             where o.buyerUuid = :buyerUuid
               and o.status = com.innbucks.marketplaceservice.order.OrderStatus.PAID
               and f.status = com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus.DELIVERED
               and i.listingId = :listingId
             order by o.createdAt asc
            """)
    List<UUID> findDeliveredOrderIdsContainingListing(@Param("buyerUuid") UUID buyerUuid,
                                                      @Param("listingId") UUID listingId,
                                                      Pageable pageable);
}
