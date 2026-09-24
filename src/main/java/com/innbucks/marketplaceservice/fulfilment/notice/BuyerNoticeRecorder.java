package com.innbucks.marketplaceservice.fulfilment.notice;

import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.notify.SellerAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Remembers, on the parcel, whether the buyer was actually told about the
 * seller's last action (V15) — so the seller's card can say "the buyer was
 * NOT told" instead of leaving them to assume.
 *
 * <p>Called from the after-commit notification listeners, so it runs on the
 * notification pool with no transaction of its own: the repository's bulk
 * UPDATE commits by itself. It bypasses the entity (and {@code @Version}) on
 * purpose — a notice landing a moment after the seller's next action must not
 * turn that action into an optimistic-lock failure. Latest notice only.
 *
 * <p>NEVER throws: it runs inside listeners that must not throw, and a record
 * that could not be written costs the seller a hint, never the buyer a message.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuyerNoticeRecorder {

    private final OrderFulfilmentRepository fulfilmentRepository;
    private final SellerAlertService sellerAlerts;

    public void record(UUID fulfilmentId, BuyerNoticeKind kind, BuyerNoticeOutcome outcome) {
        record(fulfilmentId, null, kind, outcome);
    }

    /**
     * Records the outcome and, when every channel FAILED, alerts the seller:
     * their action went through, the buyer does not know, and only the seller
     * can now tell them. ({@code NOT_SENT} is a deployment choice, not a
     * failure, so it alerts nobody.)
     */
    public void record(UUID fulfilmentId, String orderRef, BuyerNoticeKind kind,
                       BuyerNoticeOutcome outcome) {
        if (fulfilmentId == null || kind == null || outcome == null) {
            return;
        }
        try {
            fulfilmentRepository.recordBuyerNotice(fulfilmentId, kind.name(), outcome.name(),
                    Instant.now());
        } catch (RuntimeException ex) {
            log.warn("Could not record buyer notice fulfilmentId={} kind={} outcome={} cause={}",
                    fulfilmentId, kind, outcome, ex.toString());
        }
        if (outcome == BuyerNoticeOutcome.FAILED && orderRef != null) {
            try {
                fulfilmentRepository.findById(fulfilmentId).ifPresent(parcel ->
                        sellerAlerts.buyerNotReached(parcel.getMerchantId(), orderRef,
                                fulfilmentId, whatWeFailedToSay(kind)));
            } catch (RuntimeException ex) {
                log.warn("Could not alert the seller about an unreached buyer fulfilmentId={} "
                        + "cause={}", fulfilmentId, ex.toString());
            }
        }
    }

    static String whatWeFailedToSay(BuyerNoticeKind kind) {
        return switch (kind) {
            case DISPATCHED -> "that it is on its way";
            case READY_TO_COLLECT -> "that it is ready to collect";
            case DELIVERED_BY_SELLER -> "that you marked it delivered";
            case CANCELLED -> "that it was cancelled and will be refunded";
        };
    }
}
