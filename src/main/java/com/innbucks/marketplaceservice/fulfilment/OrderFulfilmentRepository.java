package com.innbucks.marketplaceservice.fulfilment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderFulfilmentRepository extends JpaRepository<OrderFulfilment, UUID>,
        JpaSpecificationExecutor<OrderFulfilment> {

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
     * delivered, how many of those were closed by SOMEONE OTHER THAN THEM, and
     * what is still open on their queue. Feeds the trust stats — every figure
     * the platform shows about a seller is COMPUTED from these rows, never
     * asserted.
     *
     * <p>{@code buyerConfirmed} counts BUYER and RECIPIENT alike (V11). The
     * figure has always measured strength of evidence — a close the seller did
     * not perform themselves — and a redeemed collection code is exactly that.
     * Counting only BUYER would have made the stat fall for every seller who
     * verified a handover properly, which is the behaviour the code exists to
     * encourage. The response field keeps its published name.
     */
    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE status = 'DELIVERED')                            AS delivered,
                   COUNT(*) FILTER (WHERE status = 'DELIVERED'
                                      AND delivered_by IN ('BUYER', 'RECIPIENT'))          AS buyerConfirmed,
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

    /**
     * The seller's open work split the way a counter and a courier see it:
     * parcels on the road, parcels waiting on the shelf, and shelf parcels
     * waiting longer than {@code overdueBefore} — the ones to chase (or close
     * as not collected) before the operator's stale-money list finds them.
     */
    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE o.delivery_method = 'DELIVERY')                  AS onTheWay,
                   COUNT(*) FILTER (WHERE o.delivery_method = 'COLLECTION')                AS readyToCollect,
                   COUNT(*) FILTER (WHERE o.delivery_method = 'COLLECTION'
                                      AND f.dispatched_at < :overdueBefore)               AS readyToCollectOverdue
              FROM order_fulfilment f
              JOIN market_order o ON o.id = f.order_id
             WHERE f.merchant_id = :merchantId
               AND f.status = 'DISPATCHED'
            """, nativeQuery = true)
    OpenParcelCounts countOpenParcels(@Param("merchantId") UUID merchantId,
                                      @Param("overdueBefore") Instant overdueBefore);

    /** Projection of {@link #countOpenParcels} — aliases must match. */
    interface OpenParcelCounts {
        long getOnTheWay();
        long getReadyToCollect();
        long getReadyToCollectOverdue();
    }

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
                (id, order_id, merchant_id, status, delivery_fee_cents, tracking_code,
                 created_at, updated_at, version)
            VALUES (:id, :orderId, :merchantId, 'PREPARING', :deliveryFeeCents, :trackingCode,
                    :now, :now, 0)
            ON CONFLICT (order_id, merchant_id) DO NOTHING
            """, nativeQuery = true)
    int openIfAbsent(@Param("id") UUID id,
                     @Param("orderId") UUID orderId,
                     @Param("merchantId") UUID merchantId,
                     @Param("deliveryFeeCents") long deliveryFeeCents,
                     @Param("trackingCode") String trackingCode,
                     @Param("now") Instant now);

    Optional<OrderFulfilment> findByTrackingCode(String trackingCode);

    /** The courier's run: one organization's parcels in a status on one kind
     *  of order, oldest first. */
    @Query("""
            SELECT f FROM OrderFulfilment f, MarketOrder o
             WHERE o.id = f.orderId
               AND f.merchantId = :merchantId
               AND f.status = :status
               AND o.deliveryMethod = :method
             ORDER BY f.dispatchedAt ASC, f.createdAt ASC
            """)
    List<OrderFulfilment> findRun(@Param("merchantId") UUID merchantId,
                                  @Param("status") FulfilmentStatus status,
                                  @Param("method") com.innbucks.marketplaceservice.delivery.DeliveryMethod method,
                                  Pageable pageable);

    /**
     * Records the courier's position — the LATEST only, overwriting the last.
     *
     * <p>A bulk UPDATE on purpose, bypassing the entity: pings arrive every few
     * seconds, and writing them through the {@code @Version}ed entity would
     * bump the version under a seller who loaded the parcel to mark it
     * delivered, failing their click with an optimistic-lock error. The entity
     * maps these columns read-only for the mirror-image reason: its saves must
     * never write a stale position back over a fresh one.
     *
     * <p>Every condition that makes a ping meaningless is in the WHERE, so the
     * update count IS the answer: still in transit, and newer than both the
     * stored point and the throttle window. 0 = ignored, never an error.
     */
    @Modifying
    @Query(value = """
            UPDATE order_fulfilment
               SET last_latitude = :latitude,
                   last_longitude = :longitude,
                   last_accuracy_m = :accuracy,
                   last_location_at = :recordedAt,
                   last_location_by = :postedBy
             WHERE id = :id
               AND status = 'DISPATCHED'
               AND (last_location_at IS NULL OR last_location_at <= :notAfter)
            """, nativeQuery = true)
    int recordLocation(@Param("id") UUID id,
                       @Param("latitude") java.math.BigDecimal latitude,
                       @Param("longitude") java.math.BigDecimal longitude,
                       @Param("accuracy") Integer accuracy,
                       @Param("recordedAt") Instant recordedAt,
                       @Param("postedBy") String postedBy,
                       @Param("notAfter") Instant notAfter);

    /**
     * Counts one wrong collection code against a parcel, atomically.
     *
     * <p>A bulk UPDATE rather than a read-modify-write through the entity (the
     * stock and rating-aggregate discipline): two sellers hammering candidates
     * at the same parcel must advance the budget by two, and a counter that
     * loses increments to a lost update is a budget an attacker never exhausts.
     * The caller runs it in its OWN transaction — see
     * {@link com.innbucks.marketplaceservice.fulfilment.collect.CollectCodeAttempts}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE order_fulfilment
               SET collect_code_attempts = collect_code_attempts + 1,
                   updated_at            = :now
             WHERE id = :id
            """, nativeQuery = true)
    int bumpCollectCodeAttempts(@Param("id") UUID id, @Param("now") Instant now);

    /** The budget spent so far, read back inside the same bumping transaction. */
    @Query(value = "SELECT collect_code_attempts FROM order_fulfilment WHERE id = :id",
            nativeQuery = true)
    Integer collectCodeAttempts(@Param("id") UUID id);

    /**
     * Records the outcome of the last buyer message a seller action sent (V15).
     * A bulk UPDATE on purpose, bypassing the entity and its {@code @Version}:
     * it runs after the action committed, on the notification pool, and must
     * never turn the seller's NEXT action into an optimistic-lock failure.
     * {@code @Transactional} because the async caller has no transaction.
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE order_fulfilment
               SET buyer_notice_kind = :kind,
                   buyer_notice_outcome = :outcome,
                   buyer_notice_at = :at
             WHERE id = :id
            """, nativeQuery = true)
    int recordBuyerNotice(@Param("id") UUID id,
                          @Param("kind") String kind,
                          @Param("outcome") String outcome,
                          @Param("at") Instant at);
}
