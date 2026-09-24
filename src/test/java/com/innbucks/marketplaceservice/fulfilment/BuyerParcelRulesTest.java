package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.dto.ParcelActions;
import com.innbucks.marketplaceservice.order.OrderStatus;
import com.innbucks.marketplaceservice.settlement.MerchantSettlement;
import com.innbucks.marketplaceservice.settlement.SettlementStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The buyer's parcel rules, over the whole matrix of parcel state x delivery
 * method x money state x dispute. Two things are pinned: each rule's allow/deny
 * and the exact refusal it produces (the endpoints throw these verbatim), and
 * PARITY — every flag in {@link BuyerParcelRules#stateOf} is exactly "that
 * rule's refusal is null", for every combination.
 */
class BuyerParcelRulesTest {

    private static final long WINDOW_DAYS = 7;
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private final BuyerParcelRules rules = new BuyerParcelRules(WINDOW_DAYS);

    private static OrderFulfilment parcel(FulfilmentStatus status, DeliveryConfirmer by,
                                          Instant deliveredAt) {
        return OrderFulfilment.builder()
                .id(UUID.randomUUID())
                .orderId(UUID.randomUUID())
                .merchantId(UUID.randomUUID())
                .status(status)
                .deliveredBy(by)
                .deliveredAt(deliveredAt)
                .unfulfilledAt(status == FulfilmentStatus.UNFULFILLED ? NOW.minusSeconds(60) : null)
                .unfulfilledBy(status == FulfilmentStatus.UNFULFILLED ? UnfulfilledBy.SELLER : null)
                .build();
    }

    private static OrderFulfilment open(FulfilmentStatus status) {
        return parcel(status, null, null);
    }

    private static MerchantSettlement money(SettlementStatus status, Instant releasableAt) {
        return MerchantSettlement.builder()
                .id(UUID.randomUUID())
                .status(status)
                .releasableAt(releasableAt)
                .build();
    }

    private static String code(BuyerParcelRules.Refusal refusal) {
        return refusal == null ? null : refusal.code();
    }

    /** Every money state a parcel can be in, including "no row". */
    private static List<MerchantSettlement> allMoney() {
        List<MerchantSettlement> out = new ArrayList<>();
        out.add(null);
        for (SettlementStatus s : SettlementStatus.values()) {
            out.add(money(s, null));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Parity: flag == (refusal == null), everywhere
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PARITY: every flag equals 'the endpoint's rule does not refuse', for every "
            + "parcel state x method x money state x dispute x delivery time")
    void flagsAreExactlyTheRules() {
        int checked = 0;
        for (FulfilmentStatus status : FulfilmentStatus.values()) {
            for (DeliveryMethod method : DeliveryMethod.values()) {
                for (MerchantSettlement money : allMoney()) {
                    for (boolean disputed : new boolean[] {false, true}) {
                        for (Instant deliveredAt : new Instant[] {null, NOW.minus(Duration.ofDays(1)),
                                NOW.minus(Duration.ofDays(8))}) {
                            OrderFulfilment p = parcel(status,
                                    status == FulfilmentStatus.DELIVERED ? DeliveryConfirmer.BUYER : null,
                                    status == FulfilmentStatus.DELIVERED ? deliveredAt : null);
                            for (OrderStatus orderStatus : OrderStatus.values()) {
                                ParcelActions a = rules.stateOf(orderStatus, method, p, money,
                                        disputed, NOW).actions();
                                assertThat(a.canConfirmReceipt())
                                        .isEqualTo(rules.confirmReceiptRefusal(p, method) == null);
                                assertThat(a.canRequestCollectCode())
                                        .isEqualTo(rules.collectCodeRefusal(p, method) == null);
                                assertThat(a.canCancel())
                                        .isEqualTo(rules.cancelRefusal(p, money) == null);
                                assertThat(a.canDispute()).isEqualTo(rules.disputeRefusal(
                                        orderStatus, p, money, disputed, NOW) == null);
                                checked++;
                            }
                        }
                    }
                }
            }
        }
        assertThat(checked).isGreaterThan(500);
    }

    // ------------------------------------------------------------------
    // Receipt
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Receipt: open parcels only, either method; a closed one refuses with the state "
            + "machine's own message")
    void confirmReceipt() {
        for (DeliveryMethod method : DeliveryMethod.values()) {
            assertThat(rules.confirmReceiptRefusal(open(FulfilmentStatus.PREPARING), method)).isNull();
            assertThat(rules.confirmReceiptRefusal(open(FulfilmentStatus.DISPATCHED), method)).isNull();
            BuyerParcelRules.Refusal closed = rules.confirmReceiptRefusal(
                    parcel(FulfilmentStatus.DELIVERED, DeliveryConfirmer.MERCHANT, NOW), method);
            assertThat(closed.code()).isEqualTo("illegal_fulfilment_state");
            assertThat(closed.message())
                    .isEqualTo("This parcel is DELIVERED and cannot move to DELIVERED");
            assertThat(code(rules.confirmReceiptRefusal(open(FulfilmentStatus.UNFULFILLED), method)))
                    .isEqualTo("illegal_fulfilment_state");
        }
    }

    // ------------------------------------------------------------------
    // Collection code
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Collection code: open COLLECTION parcels only - never DELIVERY, never a closed "
            + "or cancelled parcel")
    void collectCode() {
        for (FulfilmentStatus status : FulfilmentStatus.values()) {
            assertThat(code(rules.collectCodeRefusal(open(status), DeliveryMethod.DELIVERY)))
                    .isEqualTo("collect_code_not_applicable");
        }
        assertThat(rules.collectCodeRefusal(open(FulfilmentStatus.PREPARING),
                DeliveryMethod.COLLECTION)).isNull();
        assertThat(rules.collectCodeRefusal(open(FulfilmentStatus.DISPATCHED),
                DeliveryMethod.COLLECTION)).isNull();
        assertThat(rules.collectCodeRefusal(open(FulfilmentStatus.DELIVERED),
                DeliveryMethod.COLLECTION).message())
                .isEqualTo("This parcel has already been handed over");
        BuyerParcelRules.Refusal cancelled = rules.collectCodeRefusal(open(FulfilmentStatus.UNFULFILLED),
                DeliveryMethod.COLLECTION);
        assertThat(cancelled.code()).isEqualTo("illegal_fulfilment_state");
        assertThat(cancelled.message())
                .isEqualTo("This parcel was cancelled - there is nothing to collect");
    }

    // ------------------------------------------------------------------
    // Cancel
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Cancel: PREPARING with HELD money only; the endpoint's three refusals, verbatim")
    void cancel() {
        OrderFulfilment preparing = open(FulfilmentStatus.PREPARING);
        assertThat(rules.cancelRefusal(preparing, money(SettlementStatus.HELD, null))).isNull();

        BuyerParcelRules.Refusal sent = rules.cancelRefusal(open(FulfilmentStatus.DISPATCHED),
                money(SettlementStatus.HELD, null));
        assertThat(sent.code()).isEqualTo("parcel_not_cancellable");
        assertThat(sent.message()).isEqualTo("The seller has already sent this parcel - "
                + "contact them, or open a dispute if it does not arrive");
        assertThat(rules.cancelRefusal(open(FulfilmentStatus.UNFULFILLED), null).message())
                .isEqualTo("This parcel is UNFULFILLED and can no longer be cancelled");

        assertThat(code(rules.cancelRefusal(preparing, money(SettlementStatus.DISPUTED, null))))
                .isEqualTo("parcel_disputed");
        for (SettlementStatus s : SettlementStatus.values()) {
            if (s != SettlementStatus.HELD && s != SettlementStatus.DISPUTED) {
                assertThat(rules.cancelRefusal(preparing, money(s, null)).message())
                        .as(s.name())
                        .isEqualTo("This parcel can no longer be cancelled - contact support");
            }
        }
        // No settlement row (a pre-V10 parcel): nothing to turn around.
        assertThat(code(rules.cancelRefusal(preparing, null))).isEqualTo("parcel_not_cancellable");
    }

    // ------------------------------------------------------------------
    // Dispute
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Dispute: paid order, a settlement, arguable money, inside the window, never "
            + "disputed - refused in exactly that order")
    void dispute() {
        OrderFulfilment undelivered = open(FulfilmentStatus.DISPATCHED);
        MerchantSettlement held = money(SettlementStatus.HELD, null);

        assertThat(rules.disputeRefusal(OrderStatus.PAID, undelivered, held, false, NOW)).isNull();
        assertThat(code(rules.disputeRefusal(OrderStatus.PENDING_PAYMENT, undelivered, held,
                false, NOW))).isEqualTo("order_not_paid");
        assertThat(code(rules.disputeRefusal(OrderStatus.PAID, undelivered, null, false, NOW)))
                .isEqualTo("settlement_missing");
        assertThat(code(rules.disputeRefusal(OrderStatus.PAID, undelivered,
                money(SettlementStatus.PAID_OUT, null), false, NOW)))
                .isEqualTo("settlement_already_paid_out");
        assertThat(code(rules.disputeRefusal(OrderStatus.PAID, undelivered,
                money(SettlementStatus.REFUNDED, null), false, NOW)))
                .isEqualTo("settlement_already_refunded");
        assertThat(code(rules.disputeRefusal(OrderStatus.PAID, undelivered,
                money(SettlementStatus.DISPUTED, null), true, NOW)))
                .isEqualTo("dispute_already_raised");
        assertThat(code(rules.disputeRefusal(OrderStatus.PAID, undelivered,
                money(SettlementStatus.REFUND_DUE, null), false, NOW)))
                .isEqualTo("refund_already_due");
        // Released money is still arguable - until it is paid out.
        assertThat(rules.disputeRefusal(OrderStatus.PAID, undelivered,
                money(SettlementStatus.RELEASABLE, null), false, NOW)).isNull();
        // A dispute resolved RELEASE leaves the money RELEASABLE: only the
        // one-dispute-ever check refuses it.
        assertThat(code(rules.disputeRefusal(OrderStatus.PAID, undelivered,
                money(SettlementStatus.RELEASABLE, null), true, NOW)))
                .isEqualTo("dispute_already_raised");
    }

    @Test
    @DisplayName("The window is inclusive: open AT the deadline, closed one instant after")
    void disputeWindowBoundary() {
        Instant deliveredAt = NOW.minus(Duration.ofDays(WINDOW_DAYS));
        OrderFulfilment delivered = parcel(FulfilmentStatus.DELIVERED, DeliveryConfirmer.MERCHANT,
                deliveredAt);
        MerchantSettlement held = money(SettlementStatus.HELD, null);

        assertThat(rules.disputeRefusal(OrderStatus.PAID, delivered, held, false, NOW)).isNull();
        BuyerParcelRules.Refusal late = rules.disputeRefusal(OrderStatus.PAID, delivered, held,
                false, NOW.plusMillis(1));
        assertThat(late.code()).isEqualTo("dispute_window_closed");
        assertThat(late.message()).isEqualTo(
                "This parcel was delivered more than 7 days ago and can no longer be disputed");
    }

    // ------------------------------------------------------------------
    // The rest of the buyer's view
    // ------------------------------------------------------------------

    @Test
    @DisplayName("receivedAt: the buyer's own confirmation or a redeemed code - never the "
            + "seller's own 'delivered'")
    void receivedAt() {
        Instant at = NOW.minusSeconds(3600);
        assertThat(rules.stateOf(OrderStatus.PAID, DeliveryMethod.COLLECTION,
                parcel(FulfilmentStatus.DELIVERED, DeliveryConfirmer.RECIPIENT, at),
                money(SettlementStatus.RELEASABLE, null), false, NOW).receivedAt()).isEqualTo(at);
        assertThat(rules.stateOf(OrderStatus.PAID, DeliveryMethod.DELIVERY,
                parcel(FulfilmentStatus.DELIVERED, DeliveryConfirmer.BUYER, at),
                money(SettlementStatus.RELEASABLE, null), false, NOW).receivedAt()).isEqualTo(at);
        assertThat(rules.stateOf(OrderStatus.PAID, DeliveryMethod.DELIVERY,
                parcel(FulfilmentStatus.DELIVERED, DeliveryConfirmer.MERCHANT, at),
                money(SettlementStatus.HELD, at.plus(Duration.ofHours(168))), false, NOW)
                .receivedAt()).isNull();
        assertThat(rules.stateOf(OrderStatus.PAID, DeliveryMethod.DELIVERY,
                open(FulfilmentStatus.DISPATCHED), money(SettlementStatus.HELD, null), false, NOW)
                .receivedAt()).isNull();
    }

    @Test
    @DisplayName("A code handover: finished, received, released - only a dispute is left, until "
            + "the window closes")
    void codeHandoverState() {
        Instant at = NOW.minusSeconds(3600);
        BuyerParcelRules.BuyerParcelState state = rules.stateOf(OrderStatus.PAID,
                DeliveryMethod.COLLECTION,
                parcel(FulfilmentStatus.DELIVERED, DeliveryConfirmer.RECIPIENT, at),
                money(SettlementStatus.RELEASABLE, null), false, NOW);

        assertThat(state.actions()).isEqualTo(new ParcelActions(false, false, false, true));
        assertThat(state.closedBy()).isEqualTo(ParcelCloseMethod.COLLECTION_CODE);
        assertThat(state.closedAt()).isEqualTo(at);
        assertThat(state.receivedAt()).isEqualTo(at);
        assertThat(state.disputableUntil()).isEqualTo(at.plus(Duration.ofDays(WINDOW_DAYS)));
        // Released at the counter: no clock left to run.
        assertThat(state.paymentReleasesAt()).isNull();
    }

    @Test
    @DisplayName("A seller-marked delivery: the release clock and the dispute deadline both show")
    void sellerMarkedState() {
        Instant at = NOW.minusSeconds(3600);
        Instant releasesAt = at.plus(Duration.ofHours(168));
        BuyerParcelRules.BuyerParcelState state = rules.stateOf(OrderStatus.PAID,
                DeliveryMethod.DELIVERY,
                parcel(FulfilmentStatus.DELIVERED, DeliveryConfirmer.MERCHANT, at),
                money(SettlementStatus.HELD, releasesAt), false, NOW);

        assertThat(state.actions()).isEqualTo(new ParcelActions(false, false, false, true));
        assertThat(state.closedBy()).isEqualTo(ParcelCloseMethod.SELLER_MARKED);
        assertThat(state.receivedAt()).isNull();
        assertThat(state.paymentReleasesAt()).isEqualTo(releasesAt);
        assertThat(state.disputableUntil()).isEqualTo(at.plus(Duration.ofDays(WINDOW_DAYS)));
    }

    @Test
    @DisplayName("Past the window: no dispute, so no deadline shown either")
    void pastTheWindow() {
        Instant at = NOW.minus(Duration.ofDays(8));
        BuyerParcelRules.BuyerParcelState state = rules.stateOf(OrderStatus.PAID,
                DeliveryMethod.DELIVERY,
                parcel(FulfilmentStatus.DELIVERED, DeliveryConfirmer.BUYER, at),
                money(SettlementStatus.RELEASABLE, null), false, NOW);

        assertThat(state.actions()).isEqualTo(ParcelActions.NONE);
        assertThat(state.disputableUntil()).isNull();
        assertThat(state.closedAt()).isEqualTo(at);
    }

    @Test
    @DisplayName("A paid parcel being prepared: everything but a code is possible on a delivery")
    void preparingDelivery() {
        BuyerParcelRules.BuyerParcelState state = rules.stateOf(OrderStatus.PAID,
                DeliveryMethod.DELIVERY, open(FulfilmentStatus.PREPARING),
                money(SettlementStatus.HELD, null), false, NOW);

        assertThat(state.actions()).isEqualTo(new ParcelActions(true, false, true, true));
        assertThat(state.closedAt()).isNull();
        assertThat(state.closedBy()).isNull();
        // Undelivered: disputable with no deadline, and no release clock yet.
        assertThat(state.disputableUntil()).isNull();
        assertThat(state.paymentReleasesAt()).isNull();
    }
}
