package com.innbucks.marketplaceservice.order;

import java.util.UUID;

/**
 * In-process domain event: an order transitioned to {@link OrderStatus#PAID}.
 * Published by {@link OrderTransitionService} — THE single lifecycle
 * chokepoint — inside the confirming transaction, so the
 * {@code AFTER_COMMIT} listener ({@code notify/OrderPaidNotificationListener})
 * never fires for a confirm that then rolled back, and a future PAID path
 * cannot forget to notify (it must go through the chokepoint anyway).
 *
 * <p>Carries the snapshot the notification needs (ref, buyer contact, total)
 * so the listener composes the buyer SMS without re-reading the order row;
 * the merchant fan-out re-reads items post-commit, which is safe — the order
 * is terminal-PAID by then.
 */
public record OrderPaid(UUID orderId,
                        String orderRef,
                        String buyerMsisdn,
                        long totalCents,
                        String currency) {

    static OrderPaid of(MarketOrder order) {
        return new OrderPaid(order.getId(), order.getOrderRef(), order.getBuyerMsisdn(),
                order.getTotalCents(), order.getCurrency());
    }
}
