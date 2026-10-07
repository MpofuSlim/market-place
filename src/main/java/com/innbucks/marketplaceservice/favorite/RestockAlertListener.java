package com.innbucks.marketplaceservice.favorite;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingRestocked;
import com.innbucks.marketplaceservice.config.AsyncConfig;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.notify.FanoutCircuitBreaker;
import com.innbucks.marketplaceservice.notify.MarketplaceNotificationProperties;
import com.innbucks.marketplaceservice.notify.OrderNotificationComposer;
import com.innbucks.marketplaceservice.notify.UserNotifyGateway;
import com.innbucks.marketplaceservice.notify.UserNotifyGateway.Delivery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

/**
 * Back-in-stock alerts (phase-2 foundation, now delivering for real). Listens
 * for {@link ListingRestocked} AFTER the restocking transaction COMMITS (a
 * rolled-back cancel never alerts a ghost restock), and notifies each
 * favoriter through {@link UserNotifyGateway} — user-service owns the user's
 * contact + channel selection, so the marketplace never touches a favoriter's
 * phone or email.
 *
 * <p>Guard rails:
 * <ul>
 *   <li>{@code marketplace.notifications.restock-alerts.enabled=false} turns
 *       the trigger off ({@code outcome=disabled}); the restock metric still
 *       counts.</li>
 *   <li>Recipients are capped per event
 *       ({@code max-recipients-per-event}, default 200), oldest favorite
 *       first; the overflow is logged + metered
 *       ({@code outcome=overflow}, amount = skipped favoriters) — a viral
 *       listing must not turn one stock update into thousands of S2S
 *       calls.</li>
 *   <li>{@code @Async} on the BULK notification pool
 *       ({@link AsyncConfig#BULK_NOTIFICATION_EXECUTOR}) — up to cap-many HTTP
 *       calls must never run on the thread that committed the restock, and
 *       must never sit in front of an order-paid or parcel SMS on the
 *       per-order pool. A saturated bulk pool DROPS the event (metered), it
 *       never runs it on the committing thread.</li>
 *   <li><b>A circuit breaker over user-service</b> ({@link FanoutCircuitBreaker},
 *       {@code restock-alerts.breaker.*}). Each call costs up to the
 *       user-service connect + read timeout (2 s + 5 s), so without it a
 *       user-service outage made every one of the 200 recipients wait in turn
 *       — ~23 minutes of a bulk thread per event. Once the breaker is open the
 *       REST of the fan-out is skipped at once: counted
 *       ({@code outcome=breaker_open}, amount = skipped recipients) and logged,
 *       never retried — there is no store of pending alerts to retry from, and
 *       the next 0 &rarr; &gt;0 restock alerts again. Only no-answer / 5xx / 429
 *       and slow calls count against it; a 404 for one vanished user does
 *       not.</li>
 *   <li><b>No transaction is held while sending</b>: the listener is not
 *       {@code @Transactional}; the two lookups run in their own short
 *       read-only repository transactions before the first call.</li>
 *   <li><b>Nothing may escape an after-commit callback</b> (it would surface
 *       to the caller of a commit that already succeeded) — the listener
 *       swallows and logs; {@code UserNotifyGateway} itself never throws.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RestockAlertListener {

    private final ListingFavoriteRepository favoriteRepository;
    private final ListingRepository listingRepository;
    private final UserNotifyGateway userNotifyGateway;
    private final MarketplaceNotificationProperties properties;
    private final MarketplaceMetrics metrics;
    private final FanoutCircuitBreaker breaker;

    @Async(AsyncConfig.BULK_NOTIFICATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRestock(ListingRestocked event) {
        try {
            metrics.restockEvent();
            if (!properties.getRestockAlerts().isEnabled()) {
                metrics.notificationOutcome("restock_alert", "disabled");
                return;
            }
            Listing listing = listingRepository.findById(event.listingId()).orElse(null);
            if (listing == null) {
                return;
            }
            int cap = properties.getRestockAlerts().getMaxRecipientsPerEvent();
            long favoriters = favoriteRepository.countByIdListingId(event.listingId());
            if (favoriters == 0) {
                return;
            }
            if (favoriters > cap) {
                metrics.notificationOutcome("restock_alert", "overflow", favoriters - cap);
                log.warn("Restock alert overflow listingId={} favoriters={} cap={} — "
                        + "alerting the {} oldest favoriters only",
                        event.listingId(), favoriters, cap, cap);
            }
            List<UUID> recipients = favoriteRepository.findFavoriterUuids(
                    event.listingId(), PageRequest.of(0, cap));
            String subject = OrderNotificationComposer.restockSubject();
            String message = OrderNotificationComposer.restockMessage(listing);
            int sent = 0;
            int skipped = 0;
            for (int i = 0; i < recipients.size(); i++) {
                UUID buyerUuid = recipients.get(i);
                Delivery delivery = breaker.call(() -> userNotifyGateway.deliver(buyerUuid, subject, message));
                if (delivery == Delivery.SHORT_CIRCUITED) {
                    // user-service is clearly failing: skip the rest of this
                    // fan-out now rather than wait out a timeout per recipient.
                    skipped = recipients.size() - i;
                    metrics.notificationOutcome("restock_alert", MarketplaceMetrics.RESTOCK_BREAKER_OPEN, skipped);
                    log.warn("Restock alerts skipped listingId={} skipped={} breaker={} state={} "
                            + "(user-service notify failing; not retried)",
                            event.listingId(), skipped, breaker.name(), breaker.state());
                    break;
                }
                boolean accepted = delivery == Delivery.ACCEPTED;
                metrics.notificationOutcome("restock_alert", accepted ? "sent" : "failed");
                if (accepted) {
                    sent++;
                }
            }
            log.info("Restock alerts dispatched listingId={} recipients={} accepted={} skipped={}",
                    event.listingId(), recipients.size(), sent, skipped);
        } catch (RuntimeException ex) {
            // Notification-only path: a failure here must never look like a
            // failed cancel/update to anyone. Log and move on.
            log.warn("Restock-alert listener failed listingId={} reason={}",
                    event.listingId(), ex.getMessage());
        }
    }
}
