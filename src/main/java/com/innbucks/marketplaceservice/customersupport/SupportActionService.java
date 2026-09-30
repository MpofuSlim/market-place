package com.innbucks.marketplaceservice.customersupport;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.fulfilment.FulfilmentService;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.OrderService;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.settlement.DisputeReason;
import com.innbucks.marketplaceservice.settlement.DisputeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What support may DO for a buyer who called in: the buyer's own actions, on
 * the buyer's own rules, taken on their behalf.
 *
 * <ul>
 *   <li>Cancel an UNPAID order ({@code marketplace-support:manage}).</li>
 *   <li>Open a dispute on a parcel ({@code manage}) — deciding it stays with
 *       the operator queue (owner, 2026-09-30).</li>
 *   <li>Cancel a PAID parcel before it ships, queueing the refund
 *       ({@code marketplace-support:supervise}) — it turns money around, so it
 *       is a supervisor's call.</li>
 * </ul>
 *
 * <p><b>Nothing here has its own rule.</b> Each delegates to the service the
 * buyer's endpoint uses, which applies exactly the rule behind the buyer's
 * {@code actions} flags ({@code BuyerParcelRules} / {@code OrderStateMachine})
 * — so the flags on the order support is looking at already say which of
 * these will succeed, and support can never do what the buyer could not.
 *
 * <p>Each action requires a reason and, in ONE transaction with the action,
 * writes it as a support note on the order and logs an activity row. A refused
 * action leaves neither. The audit rows name the agent, with {@code bySupport}.
 */
@Service
@RequiredArgsConstructor
public class SupportActionService {

    static final int MAX_REASON = 255;
    static final int MAX_DISPUTE_DETAIL = 1000;

    private final SupportSubjects subjects;
    private final SupportNoteService notes;
    private final SupportActivityLog activityLog;
    private final OrderService orderService;
    private final DisputeService disputeService;
    private final FulfilmentService fulfilmentService;

    @Transactional
    public void cancelOrder(SupportAgent agent, UUID orderId, String rawReason) {
        String reason = requireText(rawReason, MAX_REASON, "reason");
        MarketOrder order = subjects.requireOrder(orderId);
        notes.addForAction(agent, SubjectKind.ORDER, orderId, "Cancelled the unpaid order for the buyer. " + reason);
        activityLog.record(agent, SupportActions.ORDER_CANCELLED, SubjectKind.ORDER, orderId,
                Map.of("orderRef", order.getOrderRef()));
        orderService.cancelBySupport(orderId);
    }

    @Transactional
    public void openDispute(SupportAgent agent, UUID orderId, UUID fulfilmentId, DisputeReason reason,
                            String rawDetail) {
        String detail = requireText(rawDetail, MAX_DISPUTE_DETAIL, "detail");
        MarketOrder order = subjects.requireOrder(orderId);
        notes.addForAction(agent, SubjectKind.ORDER, orderId,
                "Opened a dispute for the buyer (" + reason.name() + "). " + detail);
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("orderRef", order.getOrderRef());
        activity.put("fulfilmentId", fulfilmentId.toString());
        activity.put("reason", reason.name());
        activityLog.record(agent, SupportActions.DISPUTE_OPENED, SubjectKind.ORDER, orderId, activity);
        disputeService.openBySupport(agent.uuid(), orderId, fulfilmentId, reason, detail);
    }

    @Transactional
    public void cancelParcel(AuthenticatedUser caller, UUID orderId, UUID fulfilmentId, String rawReason) {
        String reason = requireText(rawReason, MAX_REASON, "reason");
        SupportAgent agent = SupportAgent.of(caller);
        MarketOrder order = subjects.requireOrder(orderId);
        notes.addForAction(agent, SubjectKind.ORDER, orderId,
                "Cancelled a parcel before dispatch for the buyer; refund queued. " + reason);
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("orderRef", order.getOrderRef());
        activity.put("fulfilmentId", fulfilmentId.toString());
        activityLog.record(agent, SupportActions.PARCEL_CANCELLED, SubjectKind.ORDER, orderId, activity);
        fulfilmentService.cancelBySupport(caller, orderId, fulfilmentId, reason);
    }

    /** Required plain text: HTML stripped, then 1..max characters. Checked
     *  before anything is read or written. */
    static String requireText(String raw, int max, String field) {
        String text = TextSanitizer.sanitize(raw);
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest(field + "_required", "Say why - the " + field
                    + " is kept as a note on the order");
        }
        if (text.length() > max) {
            throw ApiException.badRequest(field + "_too_long", "The " + field + " is at most " + max
                    + " characters");
        }
        return text;
    }
}
