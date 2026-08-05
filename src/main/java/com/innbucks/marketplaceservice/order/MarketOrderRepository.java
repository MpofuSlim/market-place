package com.innbucks.marketplaceservice.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
