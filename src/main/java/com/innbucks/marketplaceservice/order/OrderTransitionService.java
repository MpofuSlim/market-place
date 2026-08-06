package com.innbucks.marketplaceservice.order;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * THE single chokepoint for order status changes (fleet convention —
 * payment-service's {@code PaymentRecordService.transition()}). Every status
 * change validates against {@link OrderStateMachine}, mutates the row,
 * appends a {@link MarketOrderEvent} journal row IN THE SAME TRANSACTION, and
 * records the tamper-evident audit event + the outcome metric. Being at one
 * chokepoint means a future caller cannot forget any of the four.
 *
 * <p>All methods are {@code MANDATORY}-propagation: the mutation and its
 * journal row must be atomic with the caller's business change, so calling
 * without an active transaction is a programming error and fails fast.
 *
 * <p>Two refusal modes for an illegal transition — both count
 * {@code marketplace.orders.illegal_transitions} and log WARN, neither ever
 * applies the change:
 * <ul>
 *   <li>{@link #transition} THROWS 409 {@code illegal_order_state} — for
 *       caller-driven paths (buyer cancel, payments confirm) where the caller
 *       must learn the request was refused.</li>
 *   <li>{@link #transitionIfLegal} returns {@code false} — for sweeps, where
 *       losing a race (order paid between the sweep query and the row lock)
 *       is normal and must not abort the pass.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTransitionService {

    private final MarketOrderRepository orderRepository;
    private final MarketOrderEventRepository eventRepository;
    private final AuditService auditService;
    private final MarketplaceMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;

    /** Caller-path transition: refuses an illegal move with 409. */
    @Transactional(propagation = Propagation.MANDATORY)
    public MarketOrder transition(MarketOrder order, OrderStatus to, String detail) {
        if (!OrderStateMachine.isLegal(order.getStatus(), to)) {
            refuse(order, to, detail);
            throw ApiException.conflict("illegal_order_state",
                    "Order " + order.getOrderRef() + " cannot move from " + order.getStatus() + " to " + to);
        }
        return apply(order, to, detail);
    }

    /** Sweep-path transition: an illegal move is counted and skipped, never
     *  thrown — returns whether the transition was applied. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean transitionIfLegal(MarketOrder order, OrderStatus to, String detail) {
        if (!OrderStateMachine.isLegal(order.getStatus(), to)) {
            refuse(order, to, detail);
            return false;
        }
        apply(order, to, detail);
        return true;
    }

    /** Journals the birth of the order (from = null → PENDING_PAYMENT), in the
     *  creating transaction. Not a transition — no audit/metric here; the
     *  creation flow records ORDER_CREATED after its transaction commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void journalCreation(MarketOrder order) {
        journal(order.getId(), null, order.getStatus(), "Order created; stock reserved");
    }

    /** Appends an annotation row WITHOUT a status change (from == to) — for
     *  events an auditor cares about that aren't transitions, e.g. the S2S
     *  expiry extension. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void note(MarketOrder order, String detail) {
        journal(order.getId(), order.getStatus(), order.getStatus(), detail);
    }

    private void refuse(MarketOrder order, OrderStatus to, String detail) {
        metrics.illegalTransition();
        log.warn("Illegal order transition refused id={} ref={} {} -> {} detail={}",
                order.getId(), order.getOrderRef(), order.getStatus(), to, detail);
    }

    private MarketOrder apply(MarketOrder order, OrderStatus to, String detail) {
        OrderStatus from = order.getStatus();
        order.setStatus(to);
        order.setUpdatedAt(Instant.now());
        MarketOrder saved = orderRepository.save(order);
        journal(saved.getId(), from, to, detail);
        metrics.orderOutcome(to.name().toLowerCase(Locale.ROOT));
        audit(saved, from, to);
        // Notification seam (fleet convention — the middleware's LedgerService
        // publishes at ITS chokepoint for the same reason): PAID is announced
        // from the one place every PAID transition must pass through, so a
        // future confirm path cannot forget it. Published IN the transaction;
        // the AFTER_COMMIT listener never fires for a rolled-back confirm.
        if (to == OrderStatus.PAID) {
            eventPublisher.publishEvent(OrderPaid.of(saved));
        }
        log.info("order {} -> {} id={} ref={}", from, to, saved.getId(), saved.getOrderRef());
        return saved;
    }

    /**
     * A09 audit row beside the plain journal: the journal is mutable history,
     * this is the keyed-HMAC evidence trail. AuditService commits it in its own
     * REQUIRES_NEW tx and swallows its own failures, so it can neither break
     * nor block the business write. Actor is the JWT caller when there is one
     * (buyer cancel), else "system" (S2S confirm, expiry sweep).
     */
    private void audit(MarketOrder order, OrderStatus from, OrderStatus to) {
        AuditEventType type = switch (to) {
            case PAID -> AuditEventType.ORDER_PAID;
            case CANCELLED -> AuditEventType.ORDER_CANCELLED;
            case EXPIRED -> AuditEventType.ORDER_EXPIRED;
            default -> null; // no other targets are reachable per the state machine
        };
        if (type == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("from", from.name());
        metadata.put("to", to.name());
        metadata.put("orderRef", order.getOrderRef());
        metadata.put("totalCents", order.getTotalCents());
        metadata.put("currency", order.getCurrency());
        if (order.getPaymentRef() != null) {
            metadata.put("paymentRef", order.getPaymentRef());
        }
        String actor = CurrentUser.find().map(AuthenticatedUser::uuid).orElse("system");
        auditService.record(type, actor, order.getId().toString(), metadata);
    }

    private void journal(UUID orderId, OrderStatus from, OrderStatus to, String detail) {
        eventRepository.save(MarketOrderEvent.builder()
                .orderId(orderId)
                .fromStatus(from == null ? null : from.name())
                .toStatus(to.name())
                .detail(truncate(detail))
                .createdAt(Instant.now())
                .build());
    }

    private static String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= 255 ? detail : detail.substring(0, 255);
    }
}
