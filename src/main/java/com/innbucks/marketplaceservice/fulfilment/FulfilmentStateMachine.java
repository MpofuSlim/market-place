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
 *
 * <p>{@code PREPARING → UNFULFILLED} (V12) is the seller's way out of a parcel
 * they cannot supply. It is deliberately NOT reachable from {@code DISPATCHED}:
 * once goods are with a courier, "I cannot fulfil this" has stopped being true.
 * What happens then is a delivery failure, and the buyer's dispute is the path
 * for it — one that an operator looks at, because by then the two sides can
 * disagree about what happened.
 */
public final class FulfilmentStateMachine {

    private static final Map<FulfilmentStatus, Set<FulfilmentStatus>> LEGAL_TRANSITIONS = Map.of(
            FulfilmentStatus.PREPARING,
                    EnumSet.of(FulfilmentStatus.DISPATCHED, FulfilmentStatus.DELIVERED,
                            FulfilmentStatus.UNFULFILLED),
            FulfilmentStatus.DISPATCHED, EnumSet.of(FulfilmentStatus.DELIVERED),
            FulfilmentStatus.DELIVERED, Set.of(),
            FulfilmentStatus.UNFULFILLED, Set.of());

    private FulfilmentStateMachine() {
    }

    public static boolean isLegal(FulfilmentStatus from, FulfilmentStatus to) {
        return LEGAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
