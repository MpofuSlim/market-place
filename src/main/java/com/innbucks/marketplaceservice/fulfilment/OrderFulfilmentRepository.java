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
     * One seller's lifetime parcel counts in ONE scan: how many they have
     * delivered, how many of those the BUYER confirmed, and what is still
     * open on their queue. Feeds the trust stats — every figure the platform
     * shows about a seller is COMPUTED from these rows, never asserted.
     */
    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE status = 'DELIVERED')                            AS delivered,
                   COUNT(*) FILTER (WHERE status = 'DELIVERED'
                                      AND delivered_by = 'BUYER')                          AS buyerConfirmed,
                   COUNT(*) FILTER (WHERE status = 'PREPARING')                            AS awaitingDispatch,
                   COUNT(*) FILTER (WHERE status = 'DISPATCHED')                           AS inTransit
              FROM order_fulfilment
             WHERE merchant_id = :merchantId
            """, nativeQuery = true)
    ParcelCounts countParcels(@Param("merchantId") UUID merchantId);

    /**
     * Median hours from the buyer PAYING to this seller DISPATCHING, over
     * every parcel that was actually dispatched.
     *
     * <p>MEDIAN, not mean: one parcel forgotten over a holiday must not
     * poison a seller who ships same-day, and one instant dispatch must not
     * flatter a slow one. Parcels closed straight from PREPARING (handed over
     * in person — nothing was ever dispatched) are excluded; they carry no
     * dispatch to measure. The {@code dispatched_at >= paid_at} guard drops
     * rows whose clocks disagree (a backfilled or corrected row) rather than
     * feeding a negative duration into the percentile.
     */
    @Query(value = """
            SELECT percentile_cont(0.5) WITHIN GROUP (
                       ORDER BY EXTRACT(EPOCH FROM (f.dispatched_at - o.paid_at))) AS medianSeconds,
                   COUNT(*)                                                        AS sample
              FROM order_fulfilment f
              JOIN market_order o ON o.id = f.order_id
             WHERE f.merchant_id = :merchantId
               AND f.dispatched_at IS NOT NULL
               AND o.paid_at IS NOT NULL
               AND f.dispatched_at >= o.paid_at
            """, nativeQuery = true)
    DispatchTiming dispatchTiming(@Param("merchantId") UUID merchantId);

    /** Bytes-free projection of {@link #countParcels} — aliases must match. */
    interface ParcelCounts {
        long getDelivered();
        long getBuyerConfirmed();
        long getAwaitingDispatch();
        long getInTransit();
    }

    /** Projection of {@link #dispatchTiming}. {@code medianSeconds} is null
     *  when the sample is empty — Postgres' percentile over no rows. */
    interface DispatchTiming {
        Double getMedianSeconds();
        long getSample();
    }

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
