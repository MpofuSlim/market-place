package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.fulfilment.ParcelCancelledByBuyer;
import com.innbucks.marketplaceservice.settlement.DisputeOpened;
import com.innbucks.marketplaceservice.settlement.PayoutRecorded;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns money-path events into seller alerts, AFTER the transaction commits
 * (a rolled-back dispute, payout or cancellation alerts nobody) and on the
 * bounded notification pool (a slow user-service never delays the buyer's or
 * operator's request). {@link SellerAlertService} never throws, so nothing can
 * escape back into a commit.
 */
@Component
@RequiredArgsConstructor
public class SellerAlertListener {

    private final SellerAlertService alerts;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDisputeOpened(DisputeOpened event) {
        alerts.disputeOpened(event.merchantId(), event.orderRef(), event.fulfilmentId(),
                event.reason());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPayoutRecorded(PayoutRecorded event) {
        alerts.payoutSent(event.merchantId(), event.payoutReference(), event.parcels(),
                event.netCents(), event.currency());
    }

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCancelledByBuyer(ParcelCancelledByBuyer event) {
        alerts.cancelledByBuyer(event.merchantId(), event.orderRef(), event.fulfilmentId(),
                event.buyerReason());
    }
}
