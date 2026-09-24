package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the complete legal fulfilment lifecycle. Written as an exhaustive sweep
 * rather than a handful of happy cases: the whole point of a transitions map is
 * that everything NOT listed is refused, and only a sweep proves that.
 */
class FulfilmentStateMachineTest {

    @Test
    @DisplayName("PREPARING may dispatch, or go straight to delivered (handed over in person)")
    void fromPreparing() {
        assertThat(FulfilmentStateMachine.isLegal(FulfilmentStatus.PREPARING,
                FulfilmentStatus.DISPATCHED)).isTrue();
        // Legal on purpose: a seller handing goods over, or a buyer collecting
        // the same afternoon, never passes through a dispatch. Forcing one
        // would have people clicking a button describing something that did
        // not happen.
        assertThat(FulfilmentStateMachine.isLegal(FulfilmentStatus.PREPARING,
                FulfilmentStatus.DELIVERED)).isTrue();
    }

    @Test
    @DisplayName("DISPATCHED may only be delivered — never wound back to PREPARING")
    void fromDispatched() {
        assertThat(FulfilmentStateMachine.isLegal(FulfilmentStatus.DISPATCHED,
                FulfilmentStatus.DELIVERED)).isTrue();
        assertThat(FulfilmentStateMachine.isLegal(FulfilmentStatus.DISPATCHED,
                FulfilmentStatus.PREPARING)).isFalse();
    }

    @Test
    @DisplayName("For every method, a PREPARING parcel can be declined and a DELIVERED one cannot")
    void unfulfillableOnlyFromPreparing() {
        assertThat(FulfilmentStateMachine.isLegal(FulfilmentStatus.PREPARING,
                FulfilmentStatus.UNFULFILLED)).isTrue();
        // Once goods are with a courier, "I cannot fulfil this" has stopped
        // being true. What happens next is a delivery failure, and the buyer's
        // dispute is the path for it — one an operator looks at, because by
        // then the two sides can disagree about what happened.
        assertThat(FulfilmentStateMachine.isLegal(FulfilmentStatus.DISPATCHED,
                FulfilmentStatus.UNFULFILLED)).isFalse();
        assertThat(FulfilmentStateMachine.isLegal(FulfilmentStatus.DELIVERED,
                FulfilmentStatus.UNFULFILLED)).isFalse();
    }

    @Test
    @DisplayName("A COLLECTION ready at the counter can be closed as not collected")
    void collectionNoShowCanBeClosed() {
        // DISPATCHED on a collection means "set aside at the counter": the goods
        // never left, so a buyer who never came is a parcel genuinely not
        // fulfilled. It is the only exit a no-show has, because a seller cannot
        // close a collection as delivered on their own word.
        assertThat(FulfilmentStateMachine.isLegal(FulfilmentStatus.DISPATCHED,
                FulfilmentStatus.UNFULFILLED, DeliveryMethod.COLLECTION)).isTrue();
        // With a courier, the goods have left: still a dispute, never a decline.
        assertThat(FulfilmentStateMachine.isLegal(FulfilmentStatus.DISPATCHED,
                FulfilmentStatus.UNFULFILLED, DeliveryMethod.DELIVERY)).isFalse();
    }

    @Test
    @DisplayName("The delivery method widens exactly ONE edge, and DELIVERY widens none")
    void deliveryMethodWidensExactlyOneEdge() {
        for (FulfilmentStatus from : FulfilmentStatus.values()) {
            for (FulfilmentStatus to : FulfilmentStatus.values()) {
                boolean common = FulfilmentStateMachine.isLegal(from, to);
                assertThat(FulfilmentStateMachine.isLegal(from, to, DeliveryMethod.DELIVERY))
                        .as("DELIVERY %s -> %s", from, to)
                        .isEqualTo(common);
                boolean collectionOnly = from == FulfilmentStatus.DISPATCHED
                        && to == FulfilmentStatus.UNFULFILLED;
                assertThat(FulfilmentStateMachine.isLegal(from, to, DeliveryMethod.COLLECTION))
                        .as("COLLECTION %s -> %s", from, to)
                        .isEqualTo(common || collectionOnly);
            }
        }
    }

    @Test
    @DisplayName("UNFULFILLED is terminal: a declined parcel cannot be un-declined")
    void unfulfilledIsTerminal() {
        for (FulfilmentStatus to : FulfilmentStatus.values()) {
            assertThat(FulfilmentStateMachine.isLegal(FulfilmentStatus.UNFULFILLED, to))
                    .as("UNFULFILLED -> %s", to)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("DELIVERED is terminal: nothing moves out of it, including to itself")
    void deliveredIsTerminal() {
        for (FulfilmentStatus to : FulfilmentStatus.values()) {
            assertThat(FulfilmentStateMachine.isLegal(FulfilmentStatus.DELIVERED, to))
                    .as("DELIVERED -> %s", to)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("No state may transition to itself — a repeat tap is refused, never re-applied")
    void selfTransitionsAreAlwaysIllegal() {
        for (FulfilmentStatus status : FulfilmentStatus.values()) {
            assertThat(FulfilmentStateMachine.isLegal(status, status))
                    .as("%s -> itself", status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("The ordinal order is the ADVANCEMENT order — the order-level roll-up depends on it")
    void ordinalOrderIsAdvancementOrder() {
        // FulfilmentService.rollUp takes the minimum by ordinal to report the
        // LEAST advanced parcel. Reordering this enum would silently make a
        // half-shipped order report as delivered.
        assertThat(FulfilmentStatus.PREPARING.ordinal())
                .isLessThan(FulfilmentStatus.DISPATCHED.ordinal());
        assertThat(FulfilmentStatus.DISPATCHED.ordinal())
                .isLessThan(FulfilmentStatus.DELIVERED.ordinal());
        // UNFULFILLED is NOT a stage of that journey, so it must sit outside
        // the ranked run — rollUp excludes it rather than ordering it, and
        // placing it anywhere among the three would invite the next reader to
        // treat it as a stage.
        assertThat(FulfilmentStatus.UNFULFILLED.ordinal())
                .isGreaterThan(FulfilmentStatus.DELIVERED.ordinal());
    }
}
