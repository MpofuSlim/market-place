package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.fulfilment.ParcelUnfulfilled;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.fulfilment.notice.BuyerNoticeKind;
import com.innbucks.marketplaceservice.fulfilment.notice.BuyerNoticeOutcome;
import com.innbucks.marketplaceservice.fulfilment.notice.BuyerNoticeRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the buyer a seller cannot supply part of their order (V12).
 *
 * <p>This is the one notification on the marketplace the buyer cannot afford
 * to miss: without it, goods they paid for simply never arrive and the only
 * signal is an app screen they have no reason to open. The refund is queued
 * either way — the message is what stops them waiting for a parcel that is
 * not coming.
 *
 * <p>Same discipline as every other trigger here: {@code AFTER_COMMIT} so a
 * rolled-back decline announces nothing, {@code @Async} on the bounded pool so
 * a slow gateway never delays the seller's request, and NOTHING escapes — an
 * exception from an after-commit callback reaches the caller of
 * {@code commit()}, which would make a dead SMS gateway look like a failed
 * decline and leave the parcel open all over again.
 *
 * <p>Metric {@code marketplace.notifications{type=parcel_unfulfilled}}.
 */
@Slf4j
@Component
public class ParcelUnfulfilledNotificationListener {

    private final SmsNotificationClient sms;
    private final WhatsAppNotificationClient whatsApp;
    private final MarketplaceMetrics metrics;
    private final BuyerNoticeRecorder noticeRecorder;

    public ParcelUnfulfilledNotificationListener(SmsNotificationClient sms,
                                                 WhatsAppNotificationClient whatsApp,
                                                 MarketplaceMetrics metrics,
                                                 BuyerNoticeRecorder noticeRecorder) {
        this.sms = sms;
        this.whatsApp = whatsApp;
        this.metrics = metrics;
        this.noticeRecorder = noticeRecorder;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onParcelUnfulfilled(ParcelUnfulfilled event) {
        String outcome;
        try {
            outcome = notifyBuyer(event);
        } catch (RuntimeException ex) {
            outcome = "failed";
            log.warn("Unfulfilled-parcel notification failed orderRef={} cause={}",
                    event.orderRef(), ex.toString());
        }
        metrics.notificationOutcome("parcel_unfulfilled", outcome);
        // What the seller's card shows (V15): whether the buyer was actually told.
        noticeRecorder.record(event.fulfilmentId(), event.orderRef(), BuyerNoticeKind.CANCELLED,
                BuyerNoticeOutcome.fromMetricOutcome(outcome));
    }

    /** @return the metric outcome: sent / fallback / failed / disabled */
    private String notifyBuyer(ParcelUnfulfilled event) {
        if (!sms.isConfigured() && !whatsApp.isConfigured()) {
            return "disabled";
        }
        String message = OrderNotificationComposer.parcelUnfulfilledMessage(
                event.orderRef(), event.sellerReason(), event.refundDueCents(), event.currency(),
                event.notCollected());
        if (sms.isConfigured()) {
            try {
                sms.sendSms(event.buyerMsisdn(), message, event.orderRef());
                return "sent";
            } catch (RuntimeException e) {
                log.warn("Unfulfilled-parcel SMS failed for {} orderRef={}: {}",
                        MsisdnMasking.mask(event.buyerMsisdn()), event.orderRef(), e.getMessage());
            }
        }
        if (whatsApp.isConfigured()) {
            try {
                whatsApp.sendCustomNotification(event.buyerMsisdn(), message);
                return "fallback";
            } catch (RuntimeException e) {
                log.warn("Unfulfilled-parcel WhatsApp fallback failed for {} orderRef={}: {}",
                        MsisdnMasking.mask(event.buyerMsisdn()), event.orderRef(), e.getMessage());
            }
        }
        return "failed";
    }
}
