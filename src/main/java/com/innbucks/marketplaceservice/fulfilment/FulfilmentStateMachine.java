package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;

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
 * they cannot supply. On a DELIVERY order it is deliberately NOT reachable from
 * {@code DISPATCHED}: once goods are with a courier, "I cannot fulfil this" has
 * stopped being true. What happens then is a delivery failure, and the buyer's
 * dispute is the path for it — one that an operator looks at, because by then
 * the two sides can disagree about what happened.
 *
 * <p>{@code DISPATCHED → UNFULFILLED} IS legal on a COLLECTION order, where
 * DISPATCHED means "ready at the counter": the goods never left the seller, so
 * a buyer who never came is a parcel that genuinely was not fulfilled. It is
 * the exit that makes the collection handover rule safe to enforce — a
 * collection can only be closed as delivered by the buyer's code or the buyer's
 * own confirmation, so without this a no-show would hold the goods on the shelf
 * and the buyer's money in escrow with nobody able to end it.
 */
public final class FulfilmentStateMachine {

    /** Legal for EVERY delivery method. */
    private static final Map<FulfilmentStatus, Set<FulfilmentStatus>> LEGAL_TRANSITIONS = Map.of(
            FulfilmentStatus.PREPARING,
                    EnumSet.of(FulfilmentStatus.DISPATCHED, FulfilmentStatus.DELIVERED,
                            FulfilmentStatus.UNFULFILLED),
            FulfilmentStatus.DISPATCHED, EnumSet.of(FulfilmentStatus.DELIVERED),
            FulfilmentStatus.DELIVERED, Set.of(),
            FulfilmentStatus.UNFULFILLED, Set.of());

    /** Legal only on a COLLECTION order, on top of the common set. */
    private static final Map<FulfilmentStatus, Set<FulfilmentStatus>> COLLECTION_ONLY = Map.of(
            FulfilmentStatus.DISPATCHED, EnumSet.of(FulfilmentStatus.UNFULFILLED));

    private FulfilmentStateMachine() {
    }

    /** The transitions legal for every delivery method. */
    public static boolean isLegal(FulfilmentStatus from, FulfilmentStatus to) {
        return LEGAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    /** The transitions legal for a parcel of an order delivered by {@code method}. */
    public static boolean isLegal(FulfilmentStatus from, FulfilmentStatus to, DeliveryMethod method) {
        return isLegal(from, to)
                || (method == DeliveryMethod.COLLECTION
                        && COLLECTION_ONLY.getOrDefault(from, Set.of()).contains(to));
    }
}
