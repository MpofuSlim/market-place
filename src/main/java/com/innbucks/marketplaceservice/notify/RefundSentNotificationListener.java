package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.settlement.RefundSent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the buyer their refund has been SENT — SMS first, WhatsApp when SMS
 * fails.
 *
 * <p>The second half of a cancelled parcel: the buyer was told a refund "is
 * being arranged" when the seller cancelled, and this closes that promise when
 * an operator records the transfer. Same discipline as every trigger here:
 * {@code AFTER_COMMIT} so a rolled-back recording announces nothing,
 * {@code @Async} on the bounded pool, and NOTHING escapes — an exception from
 * an after-commit callback would reach the operator as a failed refund
 * recording for a refund that has in fact been recorded.
 *
 * <p>Metric {@code marketplace.notifications{type=refund_sent}}.
 */
@Slf4j
@Component
public class RefundSentNotificationListener {

    static final String TYPE = "refund_sent";

    private final SmsNotificationClient sms;
    private final WhatsAppNotificationClient whatsApp;
    private final MarketOrderRepository orders;
    private final MarketplaceMetrics metrics;

    public RefundSentNotificationListener(SmsNotificationClient sms,
                                          WhatsAppNotificationClient whatsApp,
                                          MarketOrderRepository orders,
                                          MarketplaceMetrics metrics) {
        this.sms = sms;
        this.whatsApp = whatsApp;
        this.orders = orders;
        this.metrics = metrics;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefundSent(RefundSent event) {
        try {
            notifyBuyer(event);
        } catch (RuntimeException ex) {
            metrics.notificationOutcome(TYPE, "failed");
            log.warn("Refund-sent notification failed orderId={} cause={}",
                    event.orderId(), ex.toString());
        }
    }

    private void notifyBuyer(RefundSent event) {
        if (!sms.isConfigured() && !whatsApp.isConfigured()) {
            metrics.notificationOutcome(TYPE, "disabled");
            return;
        }
        MarketOrder order = orders.findById(event.orderId()).orElse(null);
        if (order == null || order.getBuyerMsisdn() == null || order.getBuyerMsisdn().isBlank()) {
            metrics.notificationOutcome(TYPE, "no_recipient");
            return;
        }
        String message = OrderNotificationComposer.refundSentMessage(order.getOrderRef(),
                event.amountCents(), event.currency(), event.refundReference());
        if (sms.isConfigured()) {
            try {
                sms.sendSms(order.getBuyerMsisdn(), message, order.getOrderRef());
                metrics.notificationOutcome(TYPE, "sent");
                return;
            } catch (RuntimeException e) {
                log.warn("Refund-sent SMS failed for {} orderRef={}: {}",
                        MsisdnMasking.mask(order.getBuyerMsisdn()), order.getOrderRef(), e.getMessage());
            }
        }
        if (whatsApp.isConfigured()) {
            try {
                whatsApp.sendCustomNotification(order.getBuyerMsisdn(), message);
                metrics.notificationOutcome(TYPE, "fallback");
                return;
            } catch (RuntimeException e) {
                log.warn("Refund-sent WhatsApp fallback failed for {} orderRef={}: {}",
                        MsisdnMasking.mask(order.getBuyerMsisdn()), order.getOrderRef(), e.getMessage());
            }
        }
        metrics.notificationOutcome(TYPE, "failed");
    }
}
