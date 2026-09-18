package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.seller.PayoutDestinationChanged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

/**
 * Tells a seller their payout destination changed (V13).
 *
 * <p><b>This is a security notification, not a receipt.</b> Re-pointing a
 * payout is what a compromised seller account is worth taking over FOR, and
 * nothing else in the flow would show the legitimate seller anything: the next
 * payout simply goes elsewhere and they discover it by chasing money that
 * never arrived. A message that reaches them within seconds is what turns that
 * into a phone call to support instead.
 *
 * <p><b>It names no account details</b> — see {@link PayoutDestinationChanged}.
 * The point is to make a change the seller did not make impossible to miss;
 * the new destination is on their own screen, behind their own login, which is
 * exactly where someone checking a warning should have to go.
 *
 * <p>Reaches the seller through {@link MerchantAdminResolver} — the same
 * merchantId→admin-user chain the paid-order notifier uses, so a merchant
 * whose admins cannot be resolved is metered {@code outcome=no_recipients}
 * here for the same reasons and with the same diagnosis path.
 *
 * <p>Same discipline as every other trigger here: {@code AFTER_COMMIT} so a
 * rolled-back update warns nobody, {@code @Async} so a slow gateway never
 * delays the seller's own save, and NOTHING escapes — an exception from an
 * after-commit callback reaches the caller of {@code commit()} and would make
 * a dead SMS gateway look like a refused update.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutDestinationNotificationListener {

    private final MerchantAdminResolver adminResolver;
    private final UserNotifyGateway userNotifyGateway;
    private final MarketplaceMetrics metrics;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPayoutDestinationChanged(PayoutDestinationChanged event) {
        try {
            notifySeller(event);
        } catch (RuntimeException ex) {
            metrics.notificationOutcome("payout_destination", "failed");
            log.warn("Payout-destination notification failed merchantId={} cause={}",
                    event.merchantId(), ex.toString());
        }
    }

    private void notifySeller(PayoutDestinationChanged event) {
        List<UUID> admins = adminResolver.adminUserUuids(event.merchantId());
        if (admins.isEmpty()) {
            // The change still stands — this is a warning the seller cannot be
            // sent, not a reason to refuse it. Worth a line, because a seller
            // whose admins do not resolve is one who can never be warned.
            metrics.notificationOutcome("payout_destination", "no_recipients");
            log.warn("Payout destination changed but no admin users resolvable merchantId={}",
                    event.merchantId());
            return;
        }
        String subject = OrderNotificationComposer.payoutDestinationSubject();
        String message = OrderNotificationComposer.payoutDestinationMessage(
                event.method(), event.replacedAnExistingOne(), event.changedBySeller());
        for (UUID admin : admins) {
            boolean accepted = userNotifyGateway.notify(admin, subject, message);
            metrics.notificationOutcome("payout_destination", accepted ? "sent" : "failed");
        }
    }
}
