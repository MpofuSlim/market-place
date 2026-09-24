package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.notify.SellerAlertService;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Tells a seller, once, that a collection has been waiting at their counter
 * too long (V16).
 *
 * <p>The seller's stats already count these ({@code readyToCollectOverdue}),
 * but a number on a screen nobody opens reaches nobody, and a collection left
 * on the shelf is money HELD with no timer that will ever move it — a week
 * later it lands on the operator's stale list instead. The threshold is the
 * same {@code marketplace.fulfilment.collection-overdue-days} the stats use,
 * so the bell and the counter can never disagree about what "overdue" means,
 * and it sits inside the operator's stale window on purpose: the seller hears
 * first.
 *
 * <p><b>At most once per parcel.</b> Each parcel is CLAIMED
 * ({@code collection_overdue_alerted_at}) by a conditional UPDATE before the
 * alert is sent, so a crash between the two loses an alert rather than
 * repeating one every morning, and a parcel collected between the read and the
 * claim is skipped. ShedLock-elected like every other sweep here.
 *
 * <p>Never throws out of a row: one bad parcel must not starve the rest.
 */
@Slf4j
@Component
public class CollectionOverdueSweeper {

    /** Bounded per run; anything past it is picked up tomorrow. */
    static final int BATCH_LIMIT = 200;

    private final OrderFulfilmentRepository fulfilmentRepository;
    private final SellerAlertService sellerAlerts;
    private final int overdueDays;

    public CollectionOverdueSweeper(OrderFulfilmentRepository fulfilmentRepository,
                                    SellerAlertService sellerAlerts,
                                    @Value("${marketplace.fulfilment.collection-overdue-days:7}")
                                    int overdueDays) {
        this.fulfilmentRepository = fulfilmentRepository;
        this.sellerAlerts = sellerAlerts;
        this.overdueDays = overdueDays;
    }

    @Scheduled(cron = "${marketplace.scheduler.collection-overdue-cron}")
    @SchedulerLock(name = "collectionOverdueSweeper")
    public void sweep() {
        Instant now = Instant.now();
        List<OrderFulfilmentRepository.OverdueCollection> overdue = fulfilmentRepository
                .findOverdueCollections(now.minus(Duration.ofDays(overdueDays)), BATCH_LIMIT);
        int alerted = 0;
        for (OrderFulfilmentRepository.OverdueCollection parcel : overdue) {
            try {
                if (fulfilmentRepository.claimOverdueCollectionAlert(
                        parcel.getFulfilmentId(), now) == 0) {
                    continue;
                }
                sellerAlerts.collectionOverdue(parcel.getMerchantId(), parcel.getOrderRef(),
                        parcel.getFulfilmentId(), overdueDays);
                alerted++;
            } catch (RuntimeException ex) {
                log.warn("Overdue-collection alert failed fulfilmentId={} cause={}",
                        parcel.getFulfilmentId(), ex.toString());
            }
        }
        if (alerted > 0) {
            log.info("Overdue collections: alerted {} seller parcel(s) waiting over {} days",
                    alerted, overdueDays);
        }
    }
}
