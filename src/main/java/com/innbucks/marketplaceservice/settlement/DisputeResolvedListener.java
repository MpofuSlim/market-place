package com.innbucks.marketplaceservice.settlement;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.notify.UserNotifyGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Locale;

/**
 * Tells the buyer how their dispute ended — the one moment in the escrow where
 * silence would read as the platform pocketing the question.
 *
 * <p>Fleet notification discipline, verbatim: {@code AFTER_COMMIT} (a
 * rolled-back resolution never announces itself), {@code @Async} on the
 * bounded notification executor (a slow gateway must not add latency to the
 * operator's queue), and NOTHING escapes — a dead notify channel must never
 * look like a failed resolution. Delivery rides {@link UserNotifyGateway};
 * user-service owns the channel.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DisputeResolvedListener {

    private final UserNotifyGateway userNotifyGateway;
    private final MarketplaceMetrics metrics;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDisputeResolved(DisputeResolved event) {
        try {
            String order = event.orderRef() == null ? "your order" : "order " + event.orderRef();
            String subject;
            String message;
            if (event.outcome() == DisputeStatus.REFUNDED) {
                subject = "Your marketplace refund was approved";
                message = "Your dispute on " + order + " was resolved in your favour. A refund of "
                        + money(event) + " has been approved and will be processed to you.";
            } else {
                subject = "Your marketplace dispute was reviewed";
                message = "Your dispute on " + order + " was reviewed and the delivery was "
                        + "confirmed, so the payment to the seller will go ahead. If you believe "
                        + "this is wrong, please contact support.";
            }
            boolean sent = userNotifyGateway.notify(event.buyerUuid(), subject, message);
            metrics.notificationOutcome("dispute_resolved", sent ? "sent" : "failed");
        } catch (RuntimeException ex) {
            metrics.notificationOutcome("dispute_resolved", "failed");
            log.warn("Dispute-resolved notification failed disputeId={} cause={}",
                    event.disputeId(), ex.toString());
        }
    }

    /** Minor units → the human figure ("USD 25.99"). */
    private static String money(DisputeResolved event) {
        return event.currency() + " "
                + String.format(Locale.ROOT, "%.2f", event.netCents() / 100.0);
    }
}
