package com.innbucks.marketplaceservice.order;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
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
 * Lapses PENDING_PAYMENT orders whose payment TTL passed and returns their
 * reserved stock (the ticketing seat-hold philosophy: a blocked unit of stock
 * beats an oversell, but holds must always lapse). ShedLock-elected so a
 * second replica never double-fires.
 *
 * <p>Each candidate is expired in ITS OWN transaction
 * ({@link OrderService#expireOne}) with a per-row try/catch, so one poisoned
 * row (or one lost optimistic-lock race against a concurrent confirm/cancel)
 * skips that row and the sweep continues. The row transaction re-checks
 * status + expiry, so a payment confirmed between the sweep query and the
 * row lock is simply skipped — never expired.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpirySweeper {

    /** Bounded batch per pass (oldest-first) so a cold-start backlog can't
     *  turn one sweep into an unbounded scan; the next pass takes the rest. */
    private static final int BATCH_SIZE = 500;

    private final MarketOrderRepository orderRepository;
    private final OrderService orderService;
    private final MarketplaceMetrics metrics;

    @Scheduled(cron = "${marketplace.scheduler.expiry-sweep-cron}")
    @SchedulerLock(name = "orderExpirySweeper")
    public void sweep() {
        List<MarketOrder> lapsed = orderRepository.findByStatusAndExpiresAtBefore(
                OrderStatus.PENDING_PAYMENT, Instant.now(),
                PageRequest.of(0, BATCH_SIZE, Sort.by(Sort.Direction.ASC, "expiresAt")));
        if (lapsed.isEmpty()) {
            return;
        }
        int released = 0;
        for (MarketOrder order : lapsed) {
            try {
                if (orderService.expireOne(order.getId())) {
                    released++;
                }
            } catch (RuntimeException ex) {
                log.error("Expiry sweep failed for order id={} ref={} — continuing",
                        order.getId(), order.getOrderRef(), ex);
            }
        }
        metrics.expirySweep(released);
        log.info("Expiry sweep released {} of {} lapsed orders", released, lapsed.size());
    }
}
