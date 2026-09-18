package com.innbucks.marketplaceservice.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive sweep of the escrow's legal moves — the whole point of a
 * transitions map is that everything NOT listed is refused, and only a sweep
 * proves that. This machine gates MONEY, so the sweep is the one test that
 * must never be trimmed.
 */
class SettlementStateMachineTest {

    @Test
    @DisplayName("HELD may release or be disputed — nothing else")
    void fromHeld() {
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.HELD,
                SettlementStatus.RELEASABLE)).isTrue();
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.HELD,
                SettlementStatus.DISPUTED)).isTrue();
        // Money can never be PAID or REFUNDED straight out of HELD: payout
        // requires release, refund requires a dispute an operator decided.
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.HELD,
                SettlementStatus.PAID_OUT)).isFalse();
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.HELD,
                SettlementStatus.REFUNDED)).isFalse();
    }

    @Test
    @DisplayName("RELEASABLE may be paid out — or disputed, until the money actually leaves")
    void fromReleasable() {
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.RELEASABLE,
                SettlementStatus.PAID_OUT)).isTrue();
        // A buyer who confirmed receipt and then found the goods broken is
        // still inside their window until payout.
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.RELEASABLE,
                SettlementStatus.DISPUTED)).isTrue();
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.RELEASABLE,
                SettlementStatus.HELD)).isFalse();
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.RELEASABLE,
                SettlementStatus.REFUNDED)).isFalse();
    }

    @Test
    @DisplayName("DISPUTED resolves exactly two ways: the seller's (release) or the buyer's (refund)")
    void fromDisputed() {
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.DISPUTED,
                SettlementStatus.RELEASABLE)).isTrue();
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.DISPUTED,
                SettlementStatus.REFUNDED)).isTrue();
        // Never straight to PAID_OUT — a disputed parcel's money moves only
        // through an operator's explicit resolution.
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.DISPUTED,
                SettlementStatus.PAID_OUT)).isFalse();
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.DISPUTED,
                SettlementStatus.HELD)).isFalse();
    }

    @Test
    @DisplayName("PAID_OUT and REFUNDED are terminal — money that left cannot be un-sent here")
    void terminalsAreImmutable() {
        for (SettlementStatus terminal
                : new SettlementStatus[]{SettlementStatus.PAID_OUT, SettlementStatus.REFUNDED}) {
            for (SettlementStatus to : SettlementStatus.values()) {
                assertThat(SettlementStateMachine.isLegal(terminal, to))
                        .as("%s -> %s", terminal, to)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("No state transitions to itself — a repeat is refused, never re-applied")
    void selfTransitionsAreIllegal() {
        for (SettlementStatus status : SettlementStatus.values()) {
            assertThat(SettlementStateMachine.isLegal(status, status))
                    .as("%s -> itself", status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("HELD may be queued for refund; a DISPUTED row may not — that decision is already being made")
    void refundDueOnlyFromHeld() {
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.HELD,
                SettlementStatus.REFUND_DUE)).isTrue();
        // An operator resolving a dispute is already looking at it and already
        // supplies the reference; routing it through a queue of things to
        // decide would be a second decision about a decision already made.
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.DISPUTED,
                SettlementStatus.REFUND_DUE)).isFalse();
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.RELEASABLE,
                SettlementStatus.REFUND_DUE)).isFalse();
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.PAID_OUT,
                SettlementStatus.REFUND_DUE)).isFalse();
    }

    @Test
    @DisplayName("REFUND_DUE leads only to REFUNDED — money owed back cannot become the seller's")
    void refundDueLeadsOnlyToRefunded() {
        assertThat(SettlementStateMachine.isLegal(SettlementStatus.REFUND_DUE,
                SettlementStatus.REFUNDED)).isTrue();
        for (SettlementStatus to : SettlementStatus.values()) {
            if (to == SettlementStatus.REFUNDED) {
                continue;
            }
            assertThat(SettlementStateMachine.isLegal(SettlementStatus.REFUND_DUE, to))
                    .as("REFUND_DUE -> %s", to)
                    .isFalse();
        }
    }
}