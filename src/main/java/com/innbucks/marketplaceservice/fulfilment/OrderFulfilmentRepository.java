package com.innbucks.marketplaceservice.fulfilment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderFulfilmentRepository extends JpaRepository<OrderFulfilment, UUID> {

    List<OrderFulfilment> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    /** Batch load for the paged my-orders view — one query per page, not one
     *  per order. */
    List<OrderFulfilment> findByOrderIdIn(Collection<UUID> orderIds);

    Optional<OrderFulfilment> findByOrderIdAndMerchantId(UUID orderId, UUID merchantId);

    /**
     * The seller's queue. OLDEST first (FIFO) so the order that has waited
     * longest never starves behind a stream of new ones — the same stance the
     * moderation queue takes.
     */
    Page<OrderFulfilment> findByMerchantIdAndStatusOrderByCreatedAtAsc(
            UUID merchantId, FulfilmentStatus status, Pageable pageable);

    Page<OrderFulfilment> findByMerchantIdOrderByCreatedAtAsc(UUID merchantId, Pageable pageable);

    /** Fleet-oversight reads: every merchant's parcels. */
    Page<OrderFulfilment> findByStatusOrderByCreatedAtAsc(FulfilmentStatus status, Pageable pageable);

    Page<OrderFulfilment> findAllByOrderByCreatedAtAsc(Pageable pageable);

    /**
     * Opens a parcel per seller, idempotently. {@code ON CONFLICT DO NOTHING}
     * against the (order_id, merchant_id) unique index, because the only caller
     * is the payment confirm — which the payments service is free to replay,
     * and which must not double a seller's queue when it does. Native SQL
     * because JPQL has no upsert.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO order_fulfilment
                (id, order_id, merchant_id, status, created_at, updated_at, version)
            VALUES (:id, :orderId, :merchantId, 'PREPARING', :now, :now, 0)
            ON CONFLICT (order_id, merchant_id) DO NOTHING
            """, nativeQuery = true)
    int openIfAbsent(@Param("id") UUID id,
                     @Param("orderId") UUID orderId,
                     @Param("merchantId") UUID merchantId,
                     @Param("now") Instant now);
}
