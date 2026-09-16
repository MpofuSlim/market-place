package com.innbucks.marketplaceservice.settlement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementDisputeRepository extends JpaRepository<SettlementDispute, UUID> {

    Optional<SettlementDispute> findByFulfilmentId(UUID fulfilmentId);

    /** Batch load for the buyer's order view — one query per page of orders,
     *  never one per parcel. */
    List<SettlementDispute> findByFulfilmentIdIn(Collection<UUID> fulfilmentIds);

    /** The operator queue: one status, OLDEST first — the buyer who has
     *  waited longest is served first (moderation-queue stance). */
    Page<SettlementDispute> findByStatusOrderByCreatedAtAsc(DisputeStatus status, Pageable pageable);

    Page<SettlementDispute> findAllByOrderByCreatedAtAsc(Pageable pageable);
}
