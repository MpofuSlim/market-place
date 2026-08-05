package com.innbucks.marketplaceservice.order;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The complete legal order state machine (fleet convention — payment-service's
 * {@code LEGAL_TRANSITIONS} pattern). Anything not listed is illegal; the
 * terminals ({@code PAID}/{@code CANCELLED}/{@code EXPIRED}) are immutable.
 *
 * <p>Pure lookup only — refusal POLICY (count
 * {@code MarketplaceMetrics.illegalTransition()}, WARN, then throw for caller
 * paths or skip silently for sweeps) lives in {@link OrderTransitionService},
 * the single chokepoint every transition must go through.
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> LEGAL_TRANSITIONS = Map.of(
            OrderStatus.PENDING_PAYMENT,
                    EnumSet.of(OrderStatus.PAID, OrderStatus.CANCELLED, OrderStatus.EXPIRED),
            OrderStatus.PAID, Set.of(),
            OrderStatus.CANCELLED, Set.of(),
            OrderStatus.EXPIRED, Set.of());

    private OrderStateMachine() {
    }

    public static boolean isLegal(OrderStatus from, OrderStatus to) {
        return LEGAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
