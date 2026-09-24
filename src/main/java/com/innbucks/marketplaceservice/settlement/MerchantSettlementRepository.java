package com.innbucks.marketplaceservice.settlement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantSettlementRepository extends JpaRepository<MerchantSettlement, UUID>,
        JpaSpecificationExecutor<MerchantSettlement> {

    Optional<MerchantSettlement> findByFulfilmentId(UUID fulfilmentId);

    /** A page of parcels' money in ONE query — the seller's card view. */
    List<MerchantSettlement> findByFulfilmentIdIn(Collection<UUID> fulfilmentIds);

    /** The seller's money view, newest first. */
    Page<MerchantSettlement> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Page<MerchantSettlement> findByMerchantIdAndStatusOrderByCreatedAtDesc(
            UUID merchantId, SettlementStatus status, Pageable pageable);

    /** Fleet-oversight reads. */
    Page<MerchantSettlement> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<MerchantSettlement> findByStatusOrderByCreatedAtDesc(SettlementStatus status, Pageable pageable);

    /** The release sweeper's scan: HELD rows whose grace has lapsed, oldest
     *  grace first so the longest-waiting seller is released first. */
    List<MerchantSettlement> findByStatusAndReleasableAtBefore(
            SettlementStatus status, Instant cutoff, Pageable pageable);

    /** Every RELEASABLE row of one merchant — the operator's payout run loads
     *  them to transition each (journal + stamps), never a blind bulk UPDATE. */
    List<MerchantSettlement> findByMerchantIdAndStatus(UUID merchantId, SettlementStatus status);

    /**
     * The stale-escrow scan (V12): money HELD since before {@code cutoff},
     * oldest first — the rows no timer in the ledger can see, because a parcel
     * that was never delivered never gets a {@code releasable_at} for the
     * release sweeper to match. Paged so one pathological backlog cannot pull
     * an unbounded result set into a scheduled job.
     */
    List<MerchantSettlement> findByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            SettlementStatus status, Instant cutoff, Pageable pageable);

    /**
     * One merchant's money grouped by status — the seller's "where is my
     * money" summary in a single scan.
     */
    @Query("""
            select s.status as status, count(s) as parcels, sum(s.netCents) as netCents
              from MerchantSettlement s
             where s.merchantId = :merchantId
             group by s.status
            """)
    List<StatusTotal> summarize(@Param("merchantId") UUID merchantId);

    /**
     * The payout report: every merchant with RELEASABLE money, biggest owed
     * first — the sheet finance actually pays from.
     */
    @Query("""
            select s.merchantId as merchantId, count(s) as parcels, sum(s.netCents) as netCents,
                   min(s.currency) as currency
              from MerchantSettlement s
             where s.status = com.innbucks.marketplaceservice.settlement.SettlementStatus.RELEASABLE
             group by s.merchantId
             order by sum(s.netCents) desc
            """)
    List<PayoutRow> payoutReport();

    /**
     * Opens one parcel's settlement, idempotently — {@code ON CONFLICT DO
     * NOTHING} against the fulfilment unique index, because the only caller is
     * the payment confirm, which the payments service is free to replay.
     * Native SQL because JPQL has no upsert.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO merchant_settlement
                (id, order_id, fulfilment_id, merchant_id, status, gross_cents,
                 commission_cents, net_cents, delivery_fee_cents, currency,
                 created_at, updated_at, version)
            VALUES (:id, :orderId, :fulfilmentId, :merchantId, 'HELD', :grossCents,
                    :commissionCents, :netCents, :deliveryFeeCents, :currency, :now, :now, 0)
            ON CONFLICT (fulfilment_id) DO NOTHING
            """, nativeQuery = true)
    int openIfAbsent(@Param("id") UUID id,
                     @Param("orderId") UUID orderId,
                     @Param("fulfilmentId") UUID fulfilmentId,
                     @Param("merchantId") UUID merchantId,
                     @Param("grossCents") long grossCents,
                     @Param("commissionCents") long commissionCents,
                     @Param("netCents") long netCents,
                     @Param("deliveryFeeCents") long deliveryFeeCents,
                     @Param("currency") String currency,
                     @Param("now") Instant now);

    /** Projection for {@link #summarize}. */
    interface StatusTotal {
        SettlementStatus getStatus();
        long getParcels();
        long getNetCents();
    }

    /** Projection for {@link #payoutReport}. */
    interface PayoutRow {
        UUID getMerchantId();
        long getParcels();
        long getNetCents();
        String getCurrency();
    }

    /** When this seller's next HELD money clears on its own, or null when
     *  none is on a clock (it is all waiting on delivery or a dispute). */
    @Query("""
            select min(s.releasableAt) from MerchantSettlement s
             where s.merchantId = :merchantId
               and s.status = com.innbucks.marketplaceservice.settlement.SettlementStatus.HELD
               and s.releasableAt is not null""")
    Instant nextClearing(@Param("merchantId") UUID merchantId);

    /** Net HELD money whose clock runs out by {@code until}. */
    @Query("""
            select coalesce(sum(s.netCents), 0) from MerchantSettlement s
             where s.merchantId = :merchantId
               and s.status = com.innbucks.marketplaceservice.settlement.SettlementStatus.HELD
               and s.releasableAt is not null
               and s.releasableAt <= :until""")
    long netClearingBy(@Param("merchantId") UUID merchantId, @Param("until") Instant until);

    /** Payout runs, newest first — one row per payout reference. */
    @Query("""
            select s.payoutReference as payoutReference, max(s.paidOutAt) as paidOutAt,
                   count(s) as parcels, sum(s.netCents) as netCents, min(s.currency) as currency
              from MerchantSettlement s
             where s.merchantId = :merchantId
               and s.status = com.innbucks.marketplaceservice.settlement.SettlementStatus.PAID_OUT
             group by s.payoutReference
             order by max(s.paidOutAt) desc""")
    List<PayoutRun> payoutRuns(@Param("merchantId") UUID merchantId, Pageable pageable);

    interface PayoutRun {
        String getPayoutReference();
        Instant getPaidOutAt();
        long getParcels();
        long getNetCents();
        String getCurrency();
    }
}
