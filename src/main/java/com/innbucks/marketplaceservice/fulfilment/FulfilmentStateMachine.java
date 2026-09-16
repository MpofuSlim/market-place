package com.innbucks.marketplaceservice.fulfilment;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The complete legal fulfilment state machine — the same shape (and the same
 * discipline) as {@link com.innbucks.marketplaceservice.order.OrderStateMachine}:
 * anything not listed is illegal, and {@code DELIVERED} is terminal.
 *
 * <p>{@code PREPARING → DELIVERED} directly is legal on purpose. A seller who
 * hands goods over in person, or a buyer collecting the same afternoon, never
 * passes through a dispatch — forcing a DISPATCHED step first would have people
 * clicking a button that describes something that did not happen, which is how
 * a status column stops meaning anything.
 */
public final class FulfilmentStateMachine {

    private static final Map<FulfilmentStatus, Set<FulfilmentStatus>> LEGAL_TRANSITIONS = Map.of(
            FulfilmentStatus.PREPARING,
                    EnumSet.of(FulfilmentStatus.DISPATCHED, FulfilmentStatus.DELIVERED),
            FulfilmentStatus.DISPATCHED, EnumSet.of(FulfilmentStatus.DELIVERED),
            FulfilmentStatus.DELIVERED, Set.of());

    private FulfilmentStateMachine() {
    }

    public static boolean isLegal(FulfilmentStatus from, FulfilmentStatus to) {
        return LEGAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
