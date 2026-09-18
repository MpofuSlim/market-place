package com.innbucks.marketplaceservice.settlement;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The complete legal settlement state machine — same discipline as the order
 * and fulfilment maps: anything not listed is illegal, terminals are
 * immutable.
 *
 * <p>{@code RELEASABLE → DISPUTED} is legal on purpose: "releasable" only
 * means the grace lapsed or the buyer confirmed receipt, and a buyer who
 * confirmed and then found the goods broken is still inside their dispute
 * window until the money actually LEAVES ({@code PAID_OUT}). Once it has
 * left, this ledger cannot un-send it — a later complaint is a support
 * matter, not a state.
 */
public final class SettlementStateMachine {

    private static final Map<SettlementStatus, Set<SettlementStatus>> LEGAL_TRANSITIONS = Map.of(
            SettlementStatus.HELD,
                    EnumSet.of(SettlementStatus.RELEASABLE, SettlementStatus.DISPUTED,
                            SettlementStatus.REFUND_DUE),
            SettlementStatus.RELEASABLE,
                    EnumSet.of(SettlementStatus.PAID_OUT, SettlementStatus.DISPUTED),
            SettlementStatus.DISPUTED,
                    EnumSet.of(SettlementStatus.RELEASABLE, SettlementStatus.REFUNDED),
            // The seller declined the parcel: nobody is arguing, so this goes
            // straight to the operator's refund queue rather than through a
            // dispute. DISPUTED -> REFUND_DUE is deliberately NOT legal — an
            // operator resolving a dispute is already looking at it and
            // already supplies the reference, so queueing it to be decided
            // again would be a second decision about a decision already made.
            SettlementStatus.REFUND_DUE, EnumSet.of(SettlementStatus.REFUNDED),
            SettlementStatus.PAID_OUT, Set.of(),
            SettlementStatus.REFUNDED, Set.of());

    private SettlementStateMachine() {
    }

    public static boolean isLegal(SettlementStatus from, SettlementStatus to) {
        return LEGAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
