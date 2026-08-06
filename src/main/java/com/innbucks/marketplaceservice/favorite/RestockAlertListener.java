package com.innbucks.marketplaceservice.favorite;

import com.innbucks.marketplaceservice.catalog.ListingRestocked;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Restock-alert FOUNDATION (notifications are deferred in this repo — see
 * CLAUDE.md). Listens for {@link ListingRestocked} AFTER the restocking
 * transaction COMMITS (a rolled-back cancel never logs a ghost restock),
 * counts the listing's favoriters (a count query — never bytes), logs at
 * INFO, and increments {@code marketplace.restock_events}.
 *
 * <p>When back-in-stock notifications land, THIS is the seam: replace the log
 * line with a send through the fleet notification-gateway client (copied +
 * WireMock-contract-tested per the fleet convention) — the event, the metric,
 * and the favoriter query are already in place. Do NOT add an external HTTP
 * client here without that contract test.
 *
 * <p>Nothing may escape an after-commit callback (it would surface to the
 * caller of a commit that already succeeded) — the listener swallows and logs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RestockAlertListener {

    private final ListingFavoriteRepository favoriteRepository;
    private final MarketplaceMetrics metrics;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRestock(ListingRestocked event) {
        try {
            long favoriters = favoriteRepository.countByIdListingId(event.listingId());
            log.info("listing restocked (0 -> >0) listingId={} favoriters={} — "
                            + "back-in-stock notification wiring is the deliberate next step",
                    event.listingId(), favoriters);
            metrics.restockEvent();
        } catch (RuntimeException ex) {
            // Monitoring-only path: a failure here must never look like a
            // failed cancel/update to anyone. Log and move on.
            log.warn("restock-alert listener failed listingId={} reason={}",
                    event.listingId(), ex.getMessage());
        }
    }
}
