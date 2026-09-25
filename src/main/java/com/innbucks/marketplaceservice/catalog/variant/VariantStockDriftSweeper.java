package com.innbucks.marketplaceservice.catalog.variant;

import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Reports listings whose stock no longer adds up (V19) — and deliberately does
 * NOT repair them, the {@code StaleEscrowSweeper} stance.
 *
 * <p>For a listing with options, {@code listing.stock_qty} is a total that
 * {@code ListingStock} recomputes under the row lock after every movement, so
 * this service cannot make it drift. Only an out-of-band write can: an image
 * rolled back to one that has never heard of options and still moves the
 * listing's column, or a hand-written UPDATE. Any later movement of the
 * listing heals it on its own; when an operator wants it healed now:
 *
 * <pre>
 * UPDATE listing l SET stock_qty = (SELECT COALESCE(SUM(v.stock_qty), 0)
 *   FROM listing_variant v WHERE v.listing_id = l.id) WHERE l.has_variants;
 * </pre>
 *
 * Gauge {@code marketplace.stock.aggregate_drift}: 0 when healthy, from boot.
 *
 * <p>{@link #sweep} returns {@code void} ON PURPOSE, like every sweeper here:
 * ShedLock's default {@code PROXY_METHOD} mode refuses a method returning a
 * primitive ({@code LockingNotSupportedException}), so an {@code int} return
 * made every scheduled run throw before the query, and the gauge could never
 * leave 0. Tests read the gauge.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VariantStockDriftSweeper {

    private final ListingRepository listingRepository;
    private final MarketplaceMetrics metrics;

    @Scheduled(cron = "${marketplace.scheduler.variant-stock-drift-cron}")
    @SchedulerLock(name = "variantStockDriftSweeper")
    @Transactional(readOnly = true)
    public void sweep() {
        List<UUID> drifted = listingRepository.findStockDrift();
        metrics.stockDrift(drifted.size());
        if (!drifted.isEmpty()) {
            log.warn("STOCK DRIFT: {} listing(s) with options whose stock total disagrees with "
                            + "their options (first listingId={}). Only an out-of-band write can "
                            + "cause this; the next movement of each heals it.",
                    drifted.size(), drifted.getFirst());
        }
    }
}
