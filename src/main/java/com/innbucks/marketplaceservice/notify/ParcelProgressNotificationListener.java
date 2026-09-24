package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.fulfilment.FulfilmentStatus;
import com.innbucks.marketplaceservice.fulfilment.ParcelProgressed;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.notice.BuyerNoticeKind;
import com.innbucks.marketplaceservice.fulfilment.notice.BuyerNoticeOutcome;
import com.innbucks.marketplaceservice.fulfilment.notice.BuyerNoticeRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the buyer the seller moved their parcel: dispatched, ready at the
 * counter, or closed as delivered on the seller's own word.
 *
 * <p>The last is the one that matters. A seller's "delivered" does not pay
 * them at once — the escrow holds the money for the buyer's whole dispute
 * window — but that protection only works if the buyer knows the window is
 * running. Before this, nothing told them: a parcel could be closed as
 * delivered behind a buyer's back and paid out a week later without their
 * ever having had a reason to open the app.
 *
 * <p>Same discipline as every other trigger here: {@code AFTER_COMMIT} so a
 * rolled-back move announces nothing, {@code @Async} on the bounded pool so a
 * slow gateway never delays the seller's request, and NOTHING escapes — an
 * exception from an after-commit callback reaches the caller of
 * {@code commit()}, which would make a dead SMS gateway look like a failed
 * dispatch to a seller whose parcel had in fact moved.
 *
 * <p>Metric {@code marketplace.notifications{type=parcel_dispatched|parcel_delivered}}.
 */
@Slf4j
@Component
public class ParcelProgressNotificationListener {

    private final SmsNotificationClient sms;
    private final WhatsAppNotificationClient whatsApp;
    private final MarketplaceNotificationProperties properties;
    private final MarketplaceMetrics metrics;
    private final long disputeWindowDays;
    private final BuyerNoticeRecorder noticeRecorder;

    public ParcelProgressNotificationListener(SmsNotificationClient sms,
                                              WhatsAppNotificationClient whatsApp,
                                              MarketplaceNotificationProperties properties,
                                              MarketplaceMetrics metrics,
                                              @Value("${marketplace.settlement.dispute-window-days}")
                                              long disputeWindowDays,
                                              BuyerNoticeRecorder noticeRecorder) {
        this.sms = sms;
        this.whatsApp = whatsApp;
        this.properties = properties;
        this.metrics = metrics;
        this.disputeWindowDays = disputeWindowDays;
        this.noticeRecorder = noticeRecorder;
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onParcelProgressed(ParcelProgressed event) {
        String type = event.status() == FulfilmentStatus.DELIVERED
                ? "parcel_delivered" : "parcel_dispatched";
        String outcome;
        try {
            outcome = notifyBuyer(event);
        } catch (RuntimeException ex) {
            outcome = "failed";
            log.warn("Parcel update notification failed orderRef={} cause={}",
                    event.orderRef(), ex.toString());
        }
        metrics.notificationOutcome(type, outcome);
        // What the seller's card shows (V15): whether the buyer was actually told.
        noticeRecorder.record(event.fulfilmentId(), kindOf(event),
                BuyerNoticeOutcome.fromMetricOutcome(outcome));
    }

    private static BuyerNoticeKind kindOf(ParcelProgressed event) {
        if (event.status() == FulfilmentStatus.DELIVERED) {
            return BuyerNoticeKind.DELIVERED_BY_SELLER;
        }
        return event.deliveryMethod() == DeliveryMethod.COLLECTION
                ? BuyerNoticeKind.READY_TO_COLLECT : BuyerNoticeKind.DISPATCHED;
    }

    /** @return the metric outcome: sent / fallback / failed / disabled / no_recipient */
    private String notifyBuyer(ParcelProgressed event) {
        if (!properties.getParcelUpdates().isEnabled()) {
            return "disabled";
        }
        if (event.buyerMsisdn() == null || event.buyerMsisdn().isBlank()) {
            log.warn("Parcel update not sent - order has no buyer number orderRef={}",
                    event.orderRef());
            return "no_recipient";
        }
        if (!sms.isConfigured() && !whatsApp.isConfigured()) {
            return "disabled";
        }
        String message = event.status() == FulfilmentStatus.DELIVERED
                ? OrderNotificationComposer.parcelDeliveredBySellerMessage(
                        event.orderRef(), event.partOfOrder(), disputeWindowDays)
                : OrderNotificationComposer.parcelDispatchedMessage(
                        event.orderRef(), event.deliveryMethod(), event.partOfOrder());
        if (sms.isConfigured()) {
            try {
                sms.sendSms(event.buyerMsisdn(), message, event.orderRef());
                return "sent";
            } catch (RuntimeException e) {
                log.warn("Parcel update SMS failed for {} orderRef={}: {}",
                        MsisdnMasking.mask(event.buyerMsisdn()), event.orderRef(), e.getMessage());
            }
        }
        if (whatsApp.isConfigured()) {
            try {
                whatsApp.sendCustomNotification(event.buyerMsisdn(), message);
                return "fallback";
            } catch (RuntimeException e) {
                log.warn("Parcel update WhatsApp fallback failed for {} orderRef={}: {}",
                        MsisdnMasking.mask(event.buyerMsisdn()), event.orderRef(), e.getMessage());
            }
        }
        return "failed";
    }
}
