package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.fulfilment.dto.DispatchRequest;
import com.innbucks.marketplaceservice.fulfilment.dto.FulfilmentDestination;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentPageResponse;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentResponse;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderEvent;
import com.innbucks.marketplaceservice.order.MarketOrderEventRepository;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.order.dto.OrderResponse;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.settlement.MerchantSettlement;
import com.innbucks.marketplaceservice.settlement.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Fulfilment: what happens to an order AFTER the money has moved.
 *
 * <p>Before this existed, {@code PAID} was the last thing that ever happened to
 * an order. The seller had no queue to work and no way to say a parcel had been
 * sent, and the buyer's order sat on "Paid" forever with no way to tell whether
 * anything was coming. The journey simply stopped one step short of the goods
 * arriving.
 *
 * <p>Work is tracked PER SELLER — see {@link OrderFulfilment} for why — and
 * every status change goes through {@link #transition}, the single chokepoint
 * that validates against {@link FulfilmentStateMachine}, journals into the
 * order's own {@code market_order_event} history, records the tamper-evident
 * audit row and counts the metric. Being at one chokepoint means a future
 * caller cannot forget any of the four.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FulfilmentService {

    /** Same hard cap the other paged surfaces use. */
    static final int MAX_PAGE_SIZE = 50;

    private final OrderFulfilmentRepository fulfilmentRepository;
    private final MarketOrderRepository orderRepository;
    private final MarketOrderItemRepository itemRepository;
    private final MarketOrderEventRepository eventRepository;
    private final SettlementService settlementService;
    private final AuditService auditService;
    private final MarketplaceMetrics metrics;

    // ------------------------------------------------------------------
    // Opening — driven by the PAID transition
    // ------------------------------------------------------------------

    /**
     * Opens one parcel per distinct seller in the order.
     *
     * <p>{@code MANDATORY} propagation: the parcels and the PAID transition
     * must be atomic. An order that committed as paid with no parcels would be
     * money taken for goods nobody was ever asked to send — and nothing would
     * ever notice, because the seller's queue is the only place that would have
     * shown it.
     *
     * <p>Idempotent by the (order, merchant) unique index, because the payments
     * service is free to replay a confirm and must not double a seller's queue
     * when it does.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void openForOrder(MarketOrder order) {
        Instant now = Instant.now();
        int opened = 0;
        for (UUID merchantId : itemRepository.findByOrderId(order.getId()).stream()
                .map(MarketOrderItem::getMerchantId)
                .distinct()
                .sorted()   // deterministic insert order: two concurrent
                            // confirms of the same order cannot deadlock
                .toList()) {
            opened += fulfilmentRepository.openIfAbsent(UUID.randomUUID(), order.getId(),
                    merchantId, now);
        }
        if (opened > 0) {
            metrics.fulfilmentOutcome("opened", opened);
            journal(order.getId(), null, FulfilmentStatus.PREPARING,
                    opened + " parcel(s) opened for fulfilment");
            log.info("fulfilment opened orderRef={} parcels={}", order.getOrderRef(), opened);
        }
    }

    // ------------------------------------------------------------------
    // Buyer-facing reads (assembled into the order view)
    // ------------------------------------------------------------------

    /** Every parcel of one order. */
    @Transactional(readOnly = true)
    public List<OrderFulfilment> forOrder(UUID orderId) {
        return fulfilmentRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
    }

    /** Batch load for the paged my-orders view — ONE query per page, never one
     *  per order. */
    @Transactional(readOnly = true)
    public Map<UUID, List<OrderFulfilment>> forOrders(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return fulfilmentRepository.findByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(OrderFulfilment::getOrderId));
    }

    /**
     * The order-level summary: the LEAST advanced parcel.
     *
     * <p>Null when there are no parcels — an unpaid, cancelled or expired order
     * has nothing to fulfil, and null says that plainly where a PREPARING would
     * claim a seller was packing goods nobody has paid for.
     *
     * <p>Least-advanced rather than most: "delivered" must mean every parcel
     * arrived. Rolling up to the furthest-along one would tell a buyer with two
     * sellers that their order was delivered while half of it was still in a
     * warehouse.
     */
    public static FulfilmentStatus rollUp(List<OrderFulfilment> parcels) {
        return parcels.stream()
                .map(OrderFulfilment::getStatus)
                .min(java.util.Comparator.comparingInt(FulfilmentStatus::ordinal))
                .orElse(null);
    }

    // ------------------------------------------------------------------
    // Seller queue
    // ------------------------------------------------------------------

    /**
     * A seller's parcels, oldest first (FIFO — the order that has waited longest
     * never starves). SUPER_ADMIN reads every merchant's, optionally narrowed by
     * {@code merchantIdFilter}; a MERCHANT_ADMIN is scoped to their own claim
     * and the filter is IGNORED, exactly as the listing surface treats it — a
     * merchant cannot widen their own scope by sending a parameter.
     */
    @Transactional(readOnly = true)
    public MerchantFulfilmentPageResponse queue(AuthenticatedUser caller, FulfilmentStatus status,
                                                UUID merchantIdFilter, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        Page<OrderFulfilment> result;
        if (caller.isSuperAdmin()) {
            if (merchantIdFilter != null) {
                result = status == null
                        ? fulfilmentRepository.findByMerchantIdOrderByCreatedAtAsc(
                                merchantIdFilter, pageable)
                        : fulfilmentRepository.findByMerchantIdAndStatusOrderByCreatedAtAsc(
                                merchantIdFilter, status, pageable);
            } else {
                result = status == null
                        ? fulfilmentRepository.findAllByOrderByCreatedAtAsc(pageable)
                        : fulfilmentRepository.findByStatusOrderByCreatedAtAsc(status, pageable);
            }
        } else {
            UUID merchantId = requireMerchantId(caller);
            result = status == null
                    ? fulfilmentRepository.findByMerchantIdOrderByCreatedAtAsc(merchantId, pageable)
                    : fulfilmentRepository.findByMerchantIdAndStatusOrderByCreatedAtAsc(
                            merchantId, status, pageable);
        }
        return MerchantFulfilmentPageResponse.from(result.map(this::toMerchantView));
    }

    // ------------------------------------------------------------------
    // Transitions
    // ------------------------------------------------------------------

    /** Seller sends the parcel (or sets it aside for collection). */
    @Transactional
    public MerchantFulfilmentResponse dispatch(AuthenticatedUser caller, UUID fulfilmentId,
                                               DispatchRequest request) {
        OrderFulfilment parcel = requireSellerScope(caller, fulfilmentId);
        String note = request == null ? null : blankToNull(TextSanitizer.sanitize(request.note()));
        transition(parcel, FulfilmentStatus.DISPATCHED,
                note == null ? "Dispatched by seller" : "Dispatched by seller: " + note, caller,
                p -> {
                    p.setDispatchNote(note);
                    p.setDispatchedAt(Instant.now());
                });
        return toMerchantView(parcel);
    }

    /** Seller marks the parcel handed over. Always available to them: a buyer
     *  who simply never opens the app must not leave a parcel open forever. */
    @Transactional
    public MerchantFulfilmentResponse markDelivered(AuthenticatedUser caller, UUID fulfilmentId) {
        OrderFulfilment parcel = requireSellerScope(caller, fulfilmentId);
        close(parcel, DeliveryConfirmer.MERCHANT, "Marked delivered by seller", caller);
        return toMerchantView(parcel);
    }

    /**
     * Buyer confirms they received the parcel — the last step of the journey,
     * and the one that makes {@code DELIVERED} mean something a dispute can
     * lean on (see {@link DeliveryConfirmer}).
     */
    @Transactional
    public OrderFulfilment confirmReceived(AuthenticatedUser buyer, UUID fulfilmentId) {
        OrderFulfilment parcel = fulfilmentRepository.findById(fulfilmentId)
                .orElseThrow(FulfilmentService::notFound);
        MarketOrder order = orderRepository.findById(parcel.getOrderId())
                .orElseThrow(FulfilmentService::notFound);
        // Owner-masked: someone else's parcel and a nonexistent one are the
        // same 404, so this is no existence oracle.
        if (!order.getBuyerUuid().equals(UUID.fromString(buyer.uuid()))) {
            throw notFound();
        }
        close(parcel, DeliveryConfirmer.BUYER, "Receipt confirmed by buyer", buyer);
        return parcel;
    }

    private void close(OrderFulfilment parcel, DeliveryConfirmer by, String detail,
                       AuthenticatedUser actor) {
        transition(parcel, FulfilmentStatus.DELIVERED, detail, actor, p -> {
            p.setDeliveredAt(Instant.now());
            p.setDeliveredBy(by);
        });
        // The escrow reacts IN the delivering transaction: a buyer's own
        // confirmation releases the seller's money now; a seller's self-close
        // starts the grace clock. Delivery and its money consequence commit
        // or roll back together.
        settlementService.onParcelDelivered(parcel);
    }

    /**
     * THE single chokepoint for a parcel's status changes: validate, mutate,
     * journal into the ORDER's own history, audit, count.
     *
     * <p>An illegal move is refused with 409 and counted, never applied — the
     * same policy {@code OrderTransitionService} applies to payment states. The
     * common case is a double-tap on Dispatch, or a seller marking delivered a
     * parcel the buyer has just confirmed; neither should silently rewrite a
     * terminal state.
     *
     * <p>{@code mutation} carries the fields that go with the move (the
     * dispatch note and stamp, the delivery stamp) and runs only AFTER the move
     * is found legal. Ordering it that way is structural rather than careful:
     * setting them first left a REFUSED parcel holding a delivery time it never
     * had, which the rollback happens to discard today but which no caller
     * should have to rely on.
     */
    private void transition(OrderFulfilment parcel, FulfilmentStatus to, String detail,
                            AuthenticatedUser actor,
                            java.util.function.Consumer<OrderFulfilment> mutation) {
        FulfilmentStatus from = parcel.getStatus();
        if (!FulfilmentStateMachine.isLegal(from, to)) {
            metrics.fulfilmentOutcome("illegal_transition", 1);
            log.warn("Illegal fulfilment transition refused id={} orderId={} {} -> {}",
                    parcel.getId(), parcel.getOrderId(), from, to);
            throw ApiException.conflict("illegal_fulfilment_state",
                    "This parcel is " + from + " and cannot move to " + to);
        }
        mutation.accept(parcel);
        parcel.setStatus(to);
        parcel.setUpdatedAt(Instant.now());
        fulfilmentRepository.save(parcel);
        journal(parcel.getOrderId(), from, to, detail);
        metrics.fulfilmentOutcome(to.name().toLowerCase(Locale.ROOT), 1);
        audit(parcel, from, to, actor);
        log.info("fulfilment {} -> {} id={} orderId={} merchantId={}",
                from, to, parcel.getId(), parcel.getOrderId(), parcel.getMerchantId());
    }

    /**
     * Fulfilment history lands in the ORDER's journal, tagged
     * {@code kind = FULFILMENT}, rather than a table of its own: "what happened
     * to this order" is one question, and answering it from two append-only
     * tables that have to be merged by timestamp is how the answer starts
     * disagreeing with itself.
     */
    private void journal(UUID orderId, FulfilmentStatus from, FulfilmentStatus to, String detail) {
        eventRepository.save(MarketOrderEvent.builder()
                .orderId(orderId)
                .kind(MarketOrderEvent.KIND_FULFILMENT)
                .fromStatus(from == null ? null : from.name())
                .toStatus(to.name())
                .detail(truncate(detail))
                .createdAt(Instant.now())
                .build());
    }

    private void audit(OrderFulfilment parcel, FulfilmentStatus from, FulfilmentStatus to,
                       AuthenticatedUser actor) {
        AuditEventType type = switch (to) {
            case DISPATCHED -> AuditEventType.FULFILMENT_DISPATCHED;
            case DELIVERED -> AuditEventType.FULFILMENT_DELIVERED;
            default -> null;
        };
        if (type == null) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("from", from.name());
        metadata.put("to", to.name());
        metadata.put("orderId", parcel.getOrderId().toString());
        metadata.put("merchantId", parcel.getMerchantId().toString());
        if (parcel.getDeliveredBy() != null) {
            metadata.put("deliveredBy", parcel.getDeliveredBy().name());
        }
        auditService.record(type, actor == null ? "system" : actor.uuid(),
                parcel.getId().toString(), metadata);
    }

    // ------------------------------------------------------------------
    // Scoping + assembly
    // ------------------------------------------------------------------

    /**
     * The caller may act on this parcel: SUPER_ADMIN on any, MERCHANT_ADMIN only
     * on their own. Not-theirs and not-found are the same 404 — a seller must
     * not be able to probe for other sellers' parcel ids.
     */
    private OrderFulfilment requireSellerScope(AuthenticatedUser caller, UUID fulfilmentId) {
        OrderFulfilment parcel = fulfilmentRepository.findById(fulfilmentId)
                .orElseThrow(FulfilmentService::notFound);
        if (caller.isSuperAdmin()) {
            return parcel;
        }
        if (!parcel.getMerchantId().equals(requireMerchantId(caller))) {
            throw notFound();
        }
        return parcel;
    }

    private MerchantFulfilmentResponse toMerchantView(OrderFulfilment parcel) {
        MarketOrder order = orderRepository.findById(parcel.getOrderId())
                .orElseThrow(FulfilmentService::notFound);
        List<MarketOrderItem> mine = itemRepository.findByOrderId(parcel.getOrderId()).stream()
                .filter(item -> parcel.getMerchantId().equals(item.getMerchantId()))
                .toList();
        long subtotal = mine.stream().mapToLong(MarketOrderItem::getLineTotalCents).sum();
        // The parcel's money state rides the seller's view (V10): the queue is
        // where "when do I get paid for this?" is asked.
        MerchantSettlement settlement = settlementService.forParcel(parcel.getId());
        return new MerchantFulfilmentResponse(
                parcel.getId(),
                order.getId(),
                order.getOrderRef(),
                parcel.getMerchantId(),
                parcel.getStatus(),
                order.getDeliveryMethod(),
                FulfilmentDestination.from(order),
                mine.stream().map(FulfilmentService::toLine).toList(),
                subtotal,
                order.getCurrency(),
                order.getPaidAt(),
                parcel.getDispatchNote(),
                parcel.getDispatchedAt(),
                parcel.getDeliveredAt(),
                parcel.getDeliveredBy(),
                parcel.getCreatedAt(),
                settlement == null ? null : settlement.getStatus(),
                settlement == null ? null : settlement.getNetCents());
    }

    static OrderResponse.Line toLine(MarketOrderItem item) {
        return new OrderResponse.Line(item.getListingId(), item.getTitleSnapshot(),
                item.getUnitPriceCents(), item.getQuantity(), item.getLineTotalCents());
    }

    /** Merchant scope comes from the JWT, never from a request body. */
    private static UUID requireMerchantId(AuthenticatedUser caller) {
        String claim = caller.merchantId();
        if (claim == null || claim.isBlank()) {
            throw ApiException.forbidden("merchant_scope_missing",
                    "Caller token carries no merchant scope");
        }
        try {
            return UUID.fromString(claim.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.forbidden("merchant_scope_missing",
                    "Caller token carries no merchant scope");
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("fulfilment_not_found", "Fulfilment not found");
    }

    private static String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= 255 ? detail : detail.substring(0, 255);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
