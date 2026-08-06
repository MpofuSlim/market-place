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

    /** Expiry-sweep candidates; the sweeper passes a bounded, oldest-first
     *  {@link Pageable} and re-checks each row inside its own transaction. */
    List<MarketOrder> findByStatusAndExpiresAtBefore(OrderStatus status, Instant cutoff, Pageable pageable);

    /**
     * The verified-purchase review gate (V5): ids of the caller's PAID orders
     * that contain the listing, oldest first — the FIRST qualifying purchase
     * is stored on the review as its provenance. Callers pass a bounded
     * {@link Pageable} (the service only needs one row). Status is pinned to
     * PAID in the query, not a parameter: PENDING/CANCELLED/EXPIRED orders
     * must never qualify a reviewer.
     */
    @Query("""
            select o.id
              from MarketOrder o
              join MarketOrderItem i on i.orderId = o.id
             where o.buyerUuid = :buyerUuid
               and o.status = com.innbucks.marketplaceservice.order.OrderStatus.PAID
               and i.listingId = :listingId
             order by o.createdAt asc
            """)
    List<UUID> findPaidOrderIdsContainingListing(@Param("buyerUuid") UUID buyerUuid,
                                                 @Param("listingId") UUID listingId,
                                                 Pageable pageable);
}
