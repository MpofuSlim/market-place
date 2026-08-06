package com.innbucks.marketplaceservice.favorite;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingRestocked;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.notify.MarketplaceNotificationProperties;
import com.innbucks.marketplaceservice.notify.OrderNotificationComposer;
import com.innbucks.marketplaceservice.notify.UserNotifyGateway;
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
 *   <li>{@code @Async} on the bounded pool — up to cap-many HTTP calls must
 *       never run on the thread that committed the restock.</li>
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

    @Async("notificationExecutor")
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
            for (UUID buyerUuid : recipients) {
                boolean accepted = userNotifyGateway.notify(buyerUuid, subject, message);
                metrics.notificationOutcome("restock_alert", accepted ? "sent" : "failed");
                if (accepted) {
                    sent++;
                }
            }
            log.info("Restock alerts dispatched listingId={} recipients={} accepted={}",
                    event.listingId(), recipients.size(), sent);
        } catch (RuntimeException ex) {
            // Notification-only path: a failure here must never look like a
            // failed cancel/update to anyone. Log and move on.
            log.warn("Restock-alert listener failed listingId={} reason={}",
                    event.listingId(), ex.getMessage());
        }
    }
}
