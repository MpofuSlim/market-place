package com.innbucks.marketplaceservice.fulfilment;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.delivery.DeliveryMethod;
import com.innbucks.marketplaceservice.fulfilment.collect.CollectCodeAttempts;
import com.innbucks.marketplaceservice.fulfilment.collect.CollectCodes;
import com.innbucks.marketplaceservice.fulfilment.dto.CollectCodeResponse;
import com.innbucks.marketplaceservice.fulfilment.dto.CollectRequest;
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
import com.innbucks.marketplaceservice.notify.CollectCodeNotifier;
import com.innbucks.marketplaceservice.settlement.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final CollectCodeAttempts collectCodeAttempts;
    private final CollectCodeNotifier collectCodeNotifier;

    /** The per-parcel online-guessing budget for a collection code. Field
     *  injection because {@code @RequiredArgsConstructor} covers final fields
     *  only — the house pattern, see {@code CartService}. */
    @Value("${marketplace.fulfilment.collect-code-max-attempts}")
    private int maxCollectAttempts;

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

    // ------------------------------------------------------------------
    // The collection handover code
    // ------------------------------------------------------------------

    /**
     * Mints a fresh collection code for one of the BUYER's parcels and returns
     * it — the only time the plaintext is ever readable.
     *
     * <p>Minting again REPLACES the live code and resets its guessing budget.
     * That is the whole recovery story for a code someone lost, and it is safe
     * because only the buyer can ask: a seller cannot mint, cannot read, and
     * therefore cannot refresh the budget they are spending.
     *
     * <p><b>Deliberately NOT {@code @Transactional}.</b> The DB work is a
     * single row write that Spring Data commits on its own, and the SMS that
     * follows is a call to an external gateway — wrapping the two together
     * would hold a pooled connection open across a network call to somebody
     * else's service, which is how a slow gateway becomes a database outage
     * (the middleware's Argon2-inside-a-transaction lesson, imported).
     */
    public CollectCodeResponse mintCollectCode(AuthenticatedUser buyer, UUID orderId,
                                               UUID fulfilmentId) {
        OrderFulfilment parcel = fulfilmentRepository.findById(fulfilmentId)
                .orElseThrow(FulfilmentService::notFound);
        MarketOrder order = orderRepository.findById(parcel.getOrderId())
                .orElseThrow(FulfilmentService::notFound);
        // Owner-masked, and the parcel must belong to the order named in the
        // path — someone else's parcel is the same 404 as a nonexistent one.
        if (!order.getId().equals(orderId)
                || !order.getBuyerUuid().equals(UUID.fromString(buyer.uuid()))) {
            throw notFound();
        }
        requireCollection(order);
        if (parcel.getStatus() == FulfilmentStatus.DELIVERED) {
            throw ApiException.conflict("illegal_fulfilment_state",
                    "This parcel has already been handed over");
        }

        String code = CollectCodes.mint();
        Instant now = Instant.now();
        parcel.setCollectCodeHash(CollectCodes.hash(code));
        parcel.setCollectCodeIssuedAt(now);
        // A fresh credential gets a fresh budget. Only the buyer reaches this,
        // so a seller working through candidates cannot reset their own cap.
        parcel.setCollectCodeAttempts(0);
        parcel.setUpdatedAt(now);
        fulfilmentRepository.save(parcel);
        metrics.collectCodeOutcome("minted");

        // To the person actually collecting when the order named one, else to
        // the buyer. Best-effort: they already hold the code in this response.
        String destination = order.getRecipientMsisdn() != null
                ? order.getRecipientMsisdn() : order.getBuyerMsisdn();
        String grouped = CollectCodes.grouped(code);
        String sentTo = collectCodeNotifier.send(destination, order.getOrderRef(), grouped);
        log.info("collect code minted parcel={} orderRef={} notified={}",
                parcel.getId(), order.getOrderRef(), sentTo != null);
        return new CollectCodeResponse(parcel.getId(), code, grouped, now, sentTo);
    }

    /**
     * The seller redeems the code the collector presented: the parcel closes as
     * {@link DeliveryConfirmer#RECIPIENT} and — because that is the strongest
     * evidence of handover the platform has — the escrow releases their money
     * on the spot rather than after the self-close grace window.
     *
     * <p>Every refusal is the same shape whatever was wrong with the code, and
     * a wrong one spends a slot of the parcel's budget. The budget matters
     * because the ONLY party who can submit a candidate is the seller holding
     * that parcel: without a cap, a seller could work through the keyspace to
     * buy themselves an instant payout for goods still sitting on their shelf.
     */
    @Transactional
    public MerchantFulfilmentResponse collect(AuthenticatedUser caller, UUID fulfilmentId,
                                              CollectRequest request) {
        OrderFulfilment parcel = requireSellerScope(caller, fulfilmentId);
        MarketOrder order = orderRepository.findById(parcel.getOrderId())
                .orElseThrow(FulfilmentService::notFound);
        requireCollection(order);
        if (parcel.getCollectCodeHash() == null) {
            throw ApiException.conflict("collect_code_unavailable",
                    "No collection code has been issued for this parcel - ask the buyer to "
                            + "generate one in their app");
        }
        if (parcel.getStatus() == FulfilmentStatus.DELIVERED) {
            // Checked before the compare so a correct code presented twice is
            // not charged an attempt for the seller's double-tap.
            throw ApiException.conflict("illegal_fulfilment_state",
                    "This parcel has already been handed over");
        }
        if (parcel.getCollectCodeAttempts() >= maxCollectAttempts) {
            metrics.collectCodeOutcome("locked");
            throw lockedException();
        }
        if (!CollectCodes.matches(request.code(), parcel.getCollectCodeHash())) {
            // Counted in its OWN transaction, so the refusal below cannot roll
            // the budget back — see CollectCodeAttempts.
            int spent = collectCodeAttempts.bumpAndCount(parcel.getId());
            metrics.collectCodeOutcome("invalid");
            log.warn("collect code rejected parcel={} merchantId={} attempts={}",
                    parcel.getId(), parcel.getMerchantId(), spent);
            if (spent >= maxCollectAttempts) {
                metrics.collectCodeOutcome("locked");
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("orderId", parcel.getOrderId().toString());
                metadata.put("merchantId", parcel.getMerchantId().toString());
                metadata.put("attempts", spent);
                auditService.record(AuditEventType.COLLECT_CODE_LOCKED, caller.uuid(),
                        parcel.getId().toString(), metadata);
            }
            throw ApiException.unprocessable("collect_code_invalid",
                    "That collection code is not valid for this parcel");
        }

        close(parcel, DeliveryConfirmer.RECIPIENT, "Collected - handover code redeemed", caller,
                p -> p.setCollectCodeRedeemedAt(Instant.now()));
        metrics.collectCodeOutcome("redeemed");
        return toMerchantView(parcel);
    }

    /** A code only means anything where somebody physically collects. */
    private static void requireCollection(MarketOrder order) {
        if (order.getDeliveryMethod() != DeliveryMethod.COLLECTION) {
            throw ApiException.conflict("collect_code_not_applicable",
                    "This is a delivery order - there is nothing to collect in person");
        }
    }

    private static ApiException lockedException() {
        return ApiException.conflict("collect_code_locked",
                "Too many wrong codes have been tried for this parcel - ask the buyer to "
                        + "generate a new one");
    }

    private void close(OrderFulfilment parcel, DeliveryConfirmer by, String detail,
                       AuthenticatedUser actor) {
        close(parcel, by, detail, actor, p -> { });
    }

    private void close(OrderFulfilment parcel, DeliveryConfirmer by, String detail,
                       AuthenticatedUser actor,
                       java.util.function.Consumer<OrderFulfilment> extra) {
        transition(parcel, FulfilmentStatus.DELIVERED, detail, actor, p -> {
            p.setDeliveredAt(Instant.now());
            p.setDeliveredBy(by);
            extra.accept(p);
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
                settlement == null ? null : settlement.getNetCents(),
                // Only where somebody is actually coming to a counter: on a
                // DELIVERY order the destination block already names who the
                // courier hands to, and a second name beside it would read as
                // a second person.
                order.getDeliveryMethod() == DeliveryMethod.COLLECTION
                        ? order.getRecipientName() : null,
                parcel.getCollectCodeHash() != null && parcel.getCollectCodeRedeemedAt() == null,
                parcel.getCollectCodeRedeemedAt());
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
