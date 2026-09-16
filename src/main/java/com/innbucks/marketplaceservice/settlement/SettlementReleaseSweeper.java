package com.innbucks.marketplaceservice.settlement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Releases HELD settlements whose grace window lapsed unchallenged — the
 * seller-closed-delivery path's clock. ShedLock-elected so a second replica
 * never double-fires; the expiry sweeper's per-row shape, for the same
 * reasons: each release runs in ITS OWN transaction and re-checks state, so
 * a dispute that lands between the sweep query and the row simply wins the
 * race, and one poisoned row skips, never aborts the pass.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementReleaseSweeper {

    /** Bounded batch, oldest grace first — the longest-waiting seller
     *  releases first, and a backlog can't become an unbounded scan. */
    private static final int BATCH_SIZE = 500;

    private final MerchantSettlementRepository settlementRepository;
    private final SettlementService settlementService;

    @Scheduled(cron = "${marketplace.scheduler.settlement-release-cron}")
    @SchedulerLock(name = "settlementReleaseSweeper")
    public void sweep() {
        List<MerchantSettlement> due = settlementRepository.findByStatusAndReleasableAtBefore(
                SettlementStatus.HELD, Instant.now(),
                PageRequest.of(0, BATCH_SIZE, Sort.by(Sort.Direction.ASC, "releasableAt")));
        if (due.isEmpty()) {
            return;
        }
        int released = 0;
        for (MerchantSettlement settlement : due) {
            try {
                if (settlementService.releaseOne(settlement.getId())) {
                    released++;
                }
            } catch (RuntimeException ex) {
                log.error("Settlement release failed id={} — continuing", settlement.getId(), ex);
            }
        }
        log.info("Settlement sweep released {} of {} due rows", released, due.size());
    }
}
