package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.OrderPaid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the buyer their order is paid — SMS to {@code order.buyerMsisdn}
 * (primary), WhatsApp fallback when SMS fails and the WhatsApp gateway is
 * configured — tells the gift RECIPIENT when the order named one (V11), then
 * fans out the merchant-side new-paid-order notifications (flag-gated, see
 * {@link MerchantOrderNotifier}).
 *
 * <p><b>Subordinate to the money movement, structurally</b> (the
 * middleware/ticketing discipline, copied deliberately):
 * <ul>
 *   <li>{@code AFTER_COMMIT}: nobody is told about a payment confirm that
 *       then rolled back.</li>
 *   <li>{@code @Async} on the bounded {@code notificationExecutor}: a slow
 *       SMS gateway must never add latency to the payments service's
 *       confirm-payment call.</li>
 *   <li><b>NOTHING may escape this listener.</b> An exception thrown from an
 *       after-commit callback propagates to the caller of {@code commit()} —
 *       which would make a dead SMS gateway look like a FAILED payment
 *       confirm to the payments service. Every branch below catches, logs and
 *       meters instead; {@code AsyncConfig}'s uncaught-exception handler is
 *       the defence-in-depth backstop.</li>
 * </ul>
 *
 * <p>Fire-and-forget: a crash between commit and send loses the message; the
 * order row + journal are the record. Metric:
 * {@code marketplace.notifications{type=order_paid,
 * outcome=sent|fallback|failed|disabled}}.
 */
@Slf4j
@Component
public class OrderPaidNotificationListener {

    private final SmsNotificationClient sms;
    private final WhatsAppNotificationClient whatsApp;
    private final MerchantOrderNotifier merchantOrderNotifier;
    private final MarketplaceMetrics metrics;

    public OrderPaidNotificationListener(SmsNotificationClient sms,
                                         WhatsAppNotificationClient whatsApp,
                                         MerchantOrderNotifier merchantOrderNotifier,
                                         MarketplaceMetrics metrics) {
        this.sms = sms;
        this.whatsApp = whatsApp;
        this.merchantOrderNotifier = merchantOrderNotifier;
        this.metrics = metrics;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPaid(OrderPaid event) {
        try {
            notifyBuyer(event);
        } catch (RuntimeException ex) {
            // Belt-and-braces: notifyBuyer already handles its own failures;
            // anything unexpected still must not escape an after-commit path.
            metrics.notificationOutcome("order_paid", "failed");
            log.warn("Order-paid buyer notification failed orderRef={} cause={}",
                    event.orderRef(), ex.toString());
        }
        // The gift half (V11), guarded independently for the same reason: a
        // buyer whose own receipt failed must not also cost the recipient
        // their only notice that something is on its way.
        try {
            notifyGiftRecipient(event);
        } catch (RuntimeException ex) {
            metrics.notificationOutcome("gift", "failed");
            log.warn("Gift recipient notification failed orderRef={} cause={}",
                    event.orderRef(), ex.toString());
        }
        // Merchant fan-out is independently guarded so a buyer SMS failure
        // can't suppress it, and vice versa. The notifier is itself
        // never-throws; the catch is defence in depth for the same reason as
        // above — nothing may escape an after-commit listener.
        try {
            merchantOrderNotifier.notifyMerchants(event);
        } catch (RuntimeException ex) {
            log.warn("Merchant order fan-out failed orderRef={} cause={}",
                    event.orderRef(), ex.toString());
        }
    }

    /**
     * Tells the RECIPIENT of a gift order that something is coming.
     *
     * <p>Without this the gift is invisible to the one person it is for: the
     * order lives in the buyer's app, the recipient has no account, and the
     * first they would know is a stranger at the door or a parcel they were
     * never told to collect.
     *
     * <p>Says nothing about price or contents — see the composer. Silent when
     * the order named no recipient, or named one without a number.
     */
    private void notifyGiftRecipient(OrderPaid event) {
        if (!event.hasContactableRecipient()) {
            return;
        }
        if (!sms.isConfigured() && !whatsApp.isConfigured()) {
            metrics.notificationOutcome("gift", "disabled");
            return;
        }
        String message = OrderNotificationComposer.giftRecipientMessage(
                event.orderRef(), event.giftMessage());
        if (sms.isConfigured()) {
            try {
                sms.sendSms(event.recipientMsisdn(), message, event.orderRef());
                metrics.notificationOutcome("gift", "sent");
                return;
            } catch (RuntimeException e) {
                log.warn("Gift SMS failed for {} orderRef={}: {}",
                        MsisdnMasking.mask(event.recipientMsisdn()), event.orderRef(),
                        e.getMessage());
            }
        }
        if (whatsApp.isConfigured()) {
            try {
                whatsApp.sendCustomNotification(event.recipientMsisdn(), message);
                metrics.notificationOutcome("gift", "fallback");
                return;
            } catch (RuntimeException e) {
                log.warn("Gift WhatsApp fallback failed for {} orderRef={}: {}",
                        MsisdnMasking.mask(event.recipientMsisdn()), event.orderRef(),
                        e.getMessage());
            }
        }
        metrics.notificationOutcome("gift", "failed");
    }

    private void notifyBuyer(OrderPaid event) {
        if (!sms.isConfigured() && !whatsApp.isConfigured()) {
            metrics.notificationOutcome("order_paid", "disabled");
            log.info("Order-paid notification skipped (no channel configured) orderRef={}",
                    event.orderRef());
            return;
        }
        String message = OrderNotificationComposer.buyerOrderPaidMessage(event);

        if (sms.isConfigured()) {
            try {
                // The order ref doubles as the gateway reference — one handle
                // to join a delivery incident back to the order.
                sms.sendSms(event.buyerMsisdn(), message, event.orderRef());
                metrics.notificationOutcome("order_paid", "sent");
                return;
            } catch (RuntimeException e) {
                log.warn("Order-paid SMS failed for {} orderRef={}, {}: {}",
                        MsisdnMasking.mask(event.buyerMsisdn()), event.orderRef(),
                        whatsApp.isConfigured() ? "falling back to WhatsApp" : "no WhatsApp fallback configured",
                        e.getMessage());
            }
        }

        if (whatsApp.isConfigured()) {
            try {
                whatsApp.sendCustomNotification(event.buyerMsisdn(), message);
                metrics.notificationOutcome("order_paid", "fallback");
                return;
            } catch (RuntimeException e) {
                log.warn("Order-paid WhatsApp fallback failed for {} orderRef={}: {}",
                        MsisdnMasking.mask(event.buyerMsisdn()), event.orderRef(), e.getMessage());
            }
        }
        metrics.notificationOutcome("order_paid", "failed");
    }
}
