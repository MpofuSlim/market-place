package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.dto.ParcelActions;
import com.innbucks.marketplaceservice.order.OrderStatus;
import com.innbucks.marketplaceservice.settlement.MerchantSettlement;
import com.innbucks.marketplaceservice.settlement.SettlementStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * What a buyer may do with one parcel — each rule written ONCE, and read by
 * both the endpoint that enforces it and the view that advertises it.
 *
 * <p><b>Why one place.</b> The app used to decide which buttons to show from
 * the parcel's stage and delivery method, re-deriving rules the server owns.
 * That works until a rule changes here and not there. Each method below
 * returns the refusal the endpoint throws (or {@code null} when the action is
 * allowed), so the endpoint and the {@code actions} flags cannot disagree: a
 * flag is exactly "this refusal is null right now".
 *
 * <p><b>What the flags do not cover.</b> Ownership — every view that carries
 * them is already scoped to the buyer — and races: a flag computed a moment ago
 * can still meet the endpoint's 409 when the seller acts in between. The
 * endpoint stays the authority; the flag is its advance notice.
 *
 * <p>The refusal codes and messages are the ones these endpoints have always
 * returned, byte for byte, so moving the rules here changed no response.
 */
@Component
public class BuyerParcelRules {

    private final Duration disputeWindow;

    public BuyerParcelRules(
            @Value("${marketplace.settlement.dispute-window-days}") long disputeWindowDays) {
        this.disputeWindow = Duration.ofDays(disputeWindowDays);
    }

    // ------------------------------------------------------------------
    // The four actions
    // ------------------------------------------------------------------

    /**
     * "I have received it": whatever the state machine allows — the very check
     * the transition makes. Open parcels only (PREPARING or DISPATCHED); a
     * parcel the seller already marked delivered is closed, so a seller's
     * close cannot be "upgraded" by the buyer (the money then waits for the
     * grace window — see {@code SettlementService.onParcelDelivered}).
     */
    public ApiException confirmReceiptRefusal(OrderFulfilment parcel, DeliveryMethod method) {
        if (!FulfilmentStateMachine.isLegal(parcel.getStatus(), FulfilmentStatus.DELIVERED, method)) {
            return ApiException.conflict("illegal_fulfilment_state", "This parcel is "
                    + parcel.getStatus() + " and cannot move to " + FulfilmentStatus.DELIVERED);
        }
        return null;
    }

    /**
     * A collection code: COLLECTION parcels that are still open. A code for a
     * DELIVERED parcel would open nothing, and one for an UNFULFILLED parcel
     * (cancelled, or never collected) would send the collector an SMS for
     * goods that are no longer theirs to collect.
     */
    public ApiException collectCodeRefusal(OrderFulfilment parcel, DeliveryMethod method) {
        if (method != DeliveryMethod.COLLECTION) {
            return ApiException.conflict("collect_code_not_applicable",
                    "This is a delivery order - there is nothing to collect in person");
        }
        if (parcel.getStatus() == FulfilmentStatus.DELIVERED) {
            return ApiException.conflict("illegal_fulfilment_state",
                    "This parcel has already been handed over");
        }
        if (parcel.getStatus() == FulfilmentStatus.UNFULFILLED) {
            return ApiException.conflict("illegal_fulfilment_state",
                    "This parcel was cancelled - there is nothing to collect");
        }
        return null;
    }

    /**
     * Calling a paid parcel off (V16): only while the seller is still preparing
     * it, and only while its money is HELD — anything else could not honestly
     * queue the refund the cancel promises.
     *
     * @param settlement the parcel's settlement row, or null when none exists
     */
    public ApiException cancelRefusal(OrderFulfilment parcel, MerchantSettlement settlement) {
        if (parcel.getStatus() != FulfilmentStatus.PREPARING) {
            return ApiException.conflict("parcel_not_cancellable", parcel.getStatus()
                    == FulfilmentStatus.DISPATCHED
                    ? "The seller has already sent this parcel - contact them, or open a dispute "
                            + "if it does not arrive"
                    : "This parcel is " + parcel.getStatus() + " and can no longer be cancelled");
        }
        if (settlement != null && settlement.getStatus() == SettlementStatus.DISPUTED) {
            return ApiException.conflict("parcel_disputed",
                    "This parcel is under dispute - our support team will settle it");
        }
        if (settlement == null || settlement.getStatus() != SettlementStatus.HELD) {
            // No row is a pre-V10 parcel: nothing recorded to turn around, so
            // cancelling would promise a refund the ledger cannot queue.
            return ApiException.conflict("parcel_not_cancellable",
                    "This parcel can no longer be cancelled - contact support");
        }
        return null;
    }

    /**
     * Opening a dispute (V10), in the order the endpoint has always checked:
     * a paid order, a settlement to freeze, money still arguable (HELD or
     * RELEASABLE — not already paid out, refunded, disputed or queued for a
     * refund), inside the window, and never disputed before (one per parcel,
     * ever).
     *
     * <p>The window is measured from the delivery stamp; an undelivered parcel
     * is disputable for as long as its money can still be stopped ("it never
     * arrived" IS the refund path).
     *
     * @param settlement      the parcel's settlement row, or null when none exists
     * @param alreadyDisputed whether a dispute row exists for the parcel
     */
    public ApiException disputeRefusal(OrderStatus orderStatus, OrderFulfilment parcel,
                                       MerchantSettlement settlement, boolean alreadyDisputed,
                                       Instant now) {
        if (orderStatus != OrderStatus.PAID) {
            // An unpaid order has no money to argue over; cancel/expiry is its path.
            return ApiException.conflict("order_not_paid", "Only a paid order can be disputed");
        }
        if (settlement == null) {
            return ApiException.conflict("settlement_missing",
                    "No settlement is recorded for this parcel yet - please contact support");
        }
        switch (settlement.getStatus()) {
            case PAID_OUT -> {
                return ApiException.conflict("settlement_already_paid_out",
                        "The seller has already been paid for this parcel - please contact support");
            }
            case REFUNDED -> {
                return ApiException.conflict("settlement_already_refunded",
                        "This parcel has already been refunded");
            }
            case DISPUTED -> {
                return ApiException.conflict("dispute_already_raised",
                        "This parcel has already been disputed");
            }
            // V12: the seller already declared they cannot supply this and the
            // money is queued to come back — say so, rather than letting the
            // open reach an illegal REFUND_DUE -> DISPUTED transition.
            case REFUND_DUE -> {
                return ApiException.conflict("refund_already_due",
                        "The seller could not supply this parcel - your refund is already being "
                                + "arranged");
            }
            default -> { /* HELD / RELEASABLE — arguable */ }
        }
        Instant until = disputableUntil(parcel);
        if (until != null && until.isBefore(now)) {
            return ApiException.conflict("dispute_window_closed",
                    "This parcel was delivered more than "
                            + disputeWindow.toDays() + " days ago and can no longer be disputed");
        }
        if (alreadyDisputed) {
            // One dispute per parcel, EVER — see SettlementDispute.
            return ApiException.conflict("dispute_already_raised",
                    "This parcel has already been disputed");
        }
        return null;
    }

    /** The end of the dispute window, or null while the parcel is undelivered
     *  (no deadline yet). */
    public Instant disputableUntil(OrderFulfilment parcel) {
        return parcel.getDeliveredAt() == null ? null : parcel.getDeliveredAt().plus(disputeWindow);
    }

    // ------------------------------------------------------------------
    // The buyer's view of one parcel
    // ------------------------------------------------------------------

    /**
     * Everything a buyer-facing parcel view says about what can happen next,
     * computed from the SAME rules the endpoints enforce.
     *
     * @param settlement      the parcel's settlement row, or null when none exists
     * @param alreadyDisputed whether a dispute row exists for the parcel
     */
    public BuyerParcelState stateOf(OrderStatus orderStatus, DeliveryMethod method,
                                    OrderFulfilment parcel, MerchantSettlement settlement,
                                    boolean alreadyDisputed, Instant now) {
        boolean canDispute = disputeRefusal(orderStatus, parcel, settlement, alreadyDisputed,
                now) == null;
        ParcelActions actions = new ParcelActions(
                confirmReceiptRefusal(parcel, method) == null,
                collectCodeRefusal(parcel, method) == null,
                cancelRefusal(parcel, settlement) == null,
                canDispute);
        ParcelCloseMethod closedBy = ParcelCloseMethod.of(parcel, method);
        return new BuyerParcelState(
                actions,
                receivedAt(parcel),
                ParcelCloseMethod.closedAt(parcel),
                closedBy,
                // A deadline only means something while the action is open.
                canDispute ? disputableUntil(parcel) : null,
                // The automatic release clock runs only on HELD money that a
                // seller's own "delivered" started; a buyer's or recipient's
                // close releases at once, and an undelivered parcel has no clock.
                settlement != null && settlement.getStatus() == SettlementStatus.HELD
                        ? settlement.getReleasableAt() : null);
    }

    /**
     * When the buyer's side confirmed the goods arrived: the buyer's own
     * "received", or the collector's code redeemed at the counter. A seller's
     * own "delivered" is NOT a receipt — it is the seller's word, which the
     * buyer can still dispute — so it leaves this null.
     */
    static Instant receivedAt(OrderFulfilment parcel) {
        if (parcel.getStatus() != FulfilmentStatus.DELIVERED || parcel.getDeliveredBy() == null) {
            return null;
        }
        return switch (parcel.getDeliveredBy()) {
            case BUYER, RECIPIENT -> parcel.getDeliveredAt();
            case MERCHANT -> null;
        };
    }

    /**
     * One parcel as the buyer's views describe it.
     *
     * @param receivedAt        when the buyer's side confirmed receipt (own
     *                          confirm or collection code); null otherwise
     * @param closedAt          when the parcel reached a terminal state; null
     *                          while open
     * @param closedBy          how it closed; null while open
     * @param disputableUntil   the dispute deadline while a dispute is possible
     *                          on a delivered parcel; null otherwise
     * @param paymentReleasesAt when HELD money releases to the seller unless a
     *                          dispute stops it; null when no clock is running
     */
    public record BuyerParcelState(ParcelActions actions, Instant receivedAt, Instant closedAt,
                                   ParcelCloseMethod closedBy, Instant disputableUntil,
                                   Instant paymentReleasesAt) {
    }
}
