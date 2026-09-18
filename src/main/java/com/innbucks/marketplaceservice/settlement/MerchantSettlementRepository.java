package com.innbucks.marketplaceservice.settlement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantSettlementRepository extends JpaRepository<MerchantSettlement, UUID> {

    Optional<MerchantSettlement> findByFulfilmentId(UUID fulfilmentId);

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
                 commission_cents, net_cents, currency, created_at, updated_at, version)
            VALUES (:id, :orderId, :fulfilmentId, :merchantId, 'HELD', :grossCents,
                    :commissionCents, :netCents, :currency, :now, :now, 0)
            ON CONFLICT (fulfilment_id) DO NOTHING
            """, nativeQuery = true)
    int openIfAbsent(@Param("id") UUID id,
                     @Param("orderId") UUID orderId,
                     @Param("fulfilmentId") UUID fulfilmentId,
                     @Param("merchantId") UUID merchantId,
                     @Param("grossCents") long grossCents,
                     @Param("commissionCents") long commissionCents,
                     @Param("netCents") long netCents,
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
}
