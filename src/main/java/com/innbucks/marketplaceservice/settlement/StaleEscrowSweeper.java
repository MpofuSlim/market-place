package com.innbucks.marketplaceservice.settlement;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Notices money nobody is moving (V12).
 *
 * <p>The escrow has exactly one timer — {@link SettlementReleaseSweeper} — and
 * it matches rows whose {@code releasable_at} has lapsed. That column is only
 * ever set when a SELLER closes a parcel as delivered, so a parcel that is
 * never delivered never gets one, and its settlement is invisible to the only
 * clock in the system. Paid a fortnight ago, never delivered, never declined,
 * never disputed: HELD forever, and before this class nothing anywhere looked
 * at it.
 *
 * <p><b>It reports; it does not decide.</b> Auto-releasing would pay a seller
 * who never delivered. Auto-refunding would punish one who is merely slow, and
 * the transfer is an operator's to make in either direction — this service
 * moves no money. So the job's whole output is a gauge and a log line loud
 * enough that somebody asks the seller, plus
 * {@code GET /marketplace/settlements/stale} to work the list.
 *
 * <p>ShedLock-elected like every other sweep here, so a second replica cannot
 * double-report. Read-only, so there is no per-row transaction to isolate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleEscrowSweeper {

    /** Bounded: the gauge only needs to distinguish "none", "some" and "a lot",
     *  and an unbounded scan is exactly what a backlog would make expensive. */
    static final int SCAN_LIMIT = 500;

    private final SettlementService settlementService;
    private final MarketplaceMetrics metrics;

    @Scheduled(cron = "${marketplace.scheduler.stale-escrow-cron}")
    @SchedulerLock(name = "staleEscrowSweeper")
    public void sweep() {
        List<MerchantSettlement> stale = settlementService.staleHeld(SCAN_LIMIT);
        metrics.staleSettlements(stale.size());
        if (stale.isEmpty()) {
            return;
        }
        long totalNet = stale.stream().mapToLong(MerchantSettlement::getNetCents).sum();
        MerchantSettlement oldest = stale.getFirst();
        log.warn("STALE ESCROW: {} settlement(s) held past the threshold, {} cents in total. "
                        + "Oldest orderId={} merchantId={} heldSince={}. Nothing releases these - "
                        + "chase the seller or refund the buyer.",
                stale.size(), totalNet, oldest.getOrderId(), oldest.getMerchantId(),
                oldest.getCreatedAt());
    }
}
