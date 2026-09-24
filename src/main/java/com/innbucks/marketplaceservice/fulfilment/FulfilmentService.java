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
import com.innbucks.marketplaceservice.fulfilment.dto.UnfulfillableRequest;
import com.innbucks.marketplaceservice.fulfilment.dto.MerchantFulfilmentResponse;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderEvent;
import com.innbucks.marketplaceservice.order.MarketOrderEventRepository;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.settlement.MerchantSettlement;
import com.innbucks.marketplaceservice.notify.CollectCodeNotifier;
import com.innbucks.marketplaceservice.settlement.SettlementService;
import com.innbucks.marketplaceservice.settlement.SettlementStatus;
import com.innbucks.marketplaceservice.fulfilment.tracking.TrackingCodes;
import com.innbucks.marketplaceservice.order.MarketOrderDeliveryFee;
import com.innbucks.marketplaceservice.order.MarketOrderDeliveryFeeRepository;
import com.innbucks.marketplaceservice.pickup.CollectionPointViews;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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


    private final OrderFulfilmentRepository fulfilmentRepository;
    private final MarketOrderRepository orderRepository;
    private final MarketOrderItemRepository itemRepository;
    private final MarketOrderEventRepository eventRepository;
    private final SettlementService settlementService;
    private final AuditService auditService;
    private final MarketplaceMetrics metrics;
    private final CollectCodeAttempts collectCodeAttempts;
    private final CollectCodeNotifier collectCodeNotifier;
    private final ParcelStockReturner stockReturner;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final MarketOrderDeliveryFeeRepository deliveryFees;
    private final MerchantParcelViewAssembler parcelViews;
    private final CollectionPointViews collectionPoints;

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
        // Each seller's delivery fee as fixed at ORDER time (V14) — the buyer
        // pays what they were quoted, whatever the seller charges today.
        Map<UUID, Long> feeByMerchant = deliveryFees.findByOrderId(order.getId()).stream()
                .collect(Collectors.toMap(
                        MarketOrderDeliveryFee::getMerchantId,
                        MarketOrderDeliveryFee::getFeeCents));
        for (UUID merchantId : itemRepository.findByOrderId(order.getId()).stream()
                .map(MarketOrderItem::getMerchantId)
                .distinct()
                .sorted()   // deterministic insert order: two concurrent
                            // confirms of the same order cannot deadlock
                .toList()) {
            // A replayed confirm hits the unique index and inserts nothing, so
            // the tracking code minted for it is simply discarded.
            opened += fulfilmentRepository.openIfAbsent(UUID.randomUUID(), order.getId(),
                    merchantId, feeByMerchant.getOrDefault(merchantId, 0L),
                    TrackingCodes.mint(), now);
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
     *
     * <p>UNFULFILLED parcels are EXCLUDED from the ranking rather than placed
     * in it (V12), because they are not a stage of the journey — they are a
     * parcel that left it. Ranking one as least-advanced would pin a two-seller
     * order on "unfulfilled" while the other half was on its way; ranking it as
     * most-advanced would let DELIVERED claim everything arrived when something
     * never will. So this summarises what is still owed, and says UNFULFILLED
     * only when nothing is.
     */
    public static FulfilmentStatus rollUp(List<OrderFulfilment> parcels) {
        List<FulfilmentStatus> live = parcels.stream()
                .map(OrderFulfilment::getStatus)
                .filter(status -> status != FulfilmentStatus.UNFULFILLED)
                .toList();
        if (live.isEmpty()) {
            // Either there are no parcels at all (nothing paid for yet) or
            // every one of them fell through: null and UNFULFILLED say those
            // two different things.
            return parcels.isEmpty() ? null : FulfilmentStatus.UNFULFILLED;
        }
        return live.stream()
                .min(java.util.Comparator.comparingInt(FulfilmentStatus::ordinal))
                .orElseThrow();
    }

    // ------------------------------------------------------------------
    // Seller queue
    // ------------------------------------------------------------------

    /**
     * One parcel by its tracking code — the portal's search box. Scoped like
     * the queue: a seller finds only their own organization's parcels, an
     * operator any. Someone else's code, a malformed code and an unknown one
     * are all the same 404, so the search is no way to learn which codes exist.
     */
    @Transactional(readOnly = true)
    public MerchantFulfilmentResponse byTrackingCode(AuthenticatedUser caller, String rawCode) {
        String code = TrackingCodes.normalize(rawCode);
        if (code == null) {
            throw notFound();
        }
        OrderFulfilment parcel = fulfilmentRepository.findByTrackingCode(code)
                .orElseThrow(FulfilmentService::notFound);
        if (!caller.isSuperAdmin() && !parcel.getMerchantId().equals(requireMerchantId(caller))) {
            throw notFound();
        }
        return toMerchantView(parcel);
    }

    // ------------------------------------------------------------------
    // Transitions
    // ------------------------------------------------------------------

    /** Seller sends the parcel (or sets it aside for collection). The buyer is
     *  told, after commit. */
    @Transactional
    public MerchantFulfilmentResponse dispatch(AuthenticatedUser caller, UUID fulfilmentId,
                                               DispatchRequest request) {
        OrderFulfilment parcel = requireSellerScope(caller, fulfilmentId);
        MarketOrder order = requireOrder(parcel);
        String note = request == null ? null : blankToNull(TextSanitizer.sanitize(request.note()));
        transition(parcel, order.getDeliveryMethod(), FulfilmentStatus.DISPATCHED,
                note == null ? "Dispatched by seller" : "Dispatched by seller: " + note, caller,
                p -> {
                    p.setDispatchNote(note);
                    p.setDispatchedAt(Instant.now());
                });
        publishProgress(order, parcel);
        return toMerchantView(parcel);
    }

    /**
     * Seller marks a DELIVERY parcel handed over — the courier case, where
     * nobody else can close it and a buyer who never opens the app must not
     * leave it open forever. The record keeps {@code deliveredBy: MERCHANT},
     * the escrow makes the money wait out the buyer's whole dispute window, and
     * the buyer is TOLD, after commit, so they know that window is running.
     *
     * <p><b>Refused on a COLLECTION order, for every caller.</b> A collection
     * happens at the seller's own counter, so "the buyer collected" is the one
     * claim a seller could make about a parcel that never left the shelf — and
     * it is also the one case where the platform can do better than take their
     * word: the buyer's collection code, which no seller surface ever sees, or
     * the buyer's own "received". Those are the only ways a collection closes as
     * delivered. SUPER_ADMIN is refused too: an operator marking a counter
     * handover they did not witness is the same unprovable claim. A buyer who
     * never came is a {@link #markUnfulfillable not-collected} close instead,
     * which returns the goods and refunds the money — the honest end of it.
     */
    @Transactional
    public MerchantFulfilmentResponse markDelivered(AuthenticatedUser caller, UUID fulfilmentId) {
        OrderFulfilment parcel = requireSellerScope(caller, fulfilmentId);
        MarketOrder order = requireOrder(parcel);
        if (order.getDeliveryMethod() == DeliveryMethod.COLLECTION) {
            metrics.fulfilmentOutcome("self_close_refused", 1);
            log.warn("Self-close of a collection parcel refused id={} orderRef={} merchantId={}",
                    parcel.getId(), order.getOrderRef(), parcel.getMerchantId());
            throw ApiException.conflict("collect_code_required",
                    "A collection is handed over with the buyer's collection code - ask them to "
                            + "show it and use Collect. If they never came, close the parcel as "
                            + "not collected instead");
        }
        close(parcel, order, DeliveryConfirmer.MERCHANT, "Marked delivered by seller", caller);
        publishProgress(order, parcel);
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
        close(parcel, order, DeliveryConfirmer.BUYER, "Receipt confirmed by buyer", buyer);
        return parcel;
    }

    /**
     * The seller declares they cannot supply this parcel (V12) — the exit V9
     * never gave them — or, on a COLLECTION order, that the buyer never came
     * for it.
     *
     * <p>Until this existed, a seller who was out of stock had two options:
     * leave the parcel open forever, or wait for the buyer to dispute. The
     * first is worse than it sounds, because V10 put the buyer's money behind
     * delivery — an open parcel is money HELD indefinitely, invisible to every
     * timer the ledger has.
     *
     * <p>Three things happen together, in one transaction, because any one of
     * them alone is a lie: the parcel ends, its units go back on the shelf, and
     * the buyer's money turns around onto the refund queue.
     *
     * <p>From PREPARING on every order. From DISPATCHED on a COLLECTION order
     * only, where it means "set aside at the counter and never picked up":
     * the goods never left, so returning them and refunding the buyer is the
     * truth, and it is the only way a no-show can end now that a seller cannot
     * self-close a collection. On a DELIVERY order a dispatched parcel is with
     * a courier, and a failed delivery is the buyer's dispute instead — see
     * {@link FulfilmentStateMachine}.
     */
    @Transactional
    public MerchantFulfilmentResponse markUnfulfillable(AuthenticatedUser caller, UUID fulfilmentId,
                                                        UnfulfillableRequest request) {
        OrderFulfilment parcel = requireSellerScope(caller, fulfilmentId);
        MarketOrder order = requireOrder(parcel);
        String reason = blankToNull(TextSanitizer.sanitize(request.reason()));
        if (reason == null) {
            // @NotBlank catches an empty field; a reason that is nothing but
            // markup survives that and arrives here empty.
            throw ApiException.badRequest("unfulfilled_reason_required",
                    "Tell the buyer why - reason is required");
        }

        // Read before the move: a collection that was waiting at the counter is
        // a buyer who never came, not a seller who could not supply.
        boolean notCollected = order.getDeliveryMethod() == DeliveryMethod.COLLECTION
                && parcel.getStatus() == FulfilmentStatus.DISPATCHED;
        transition(parcel, order.getDeliveryMethod(), FulfilmentStatus.UNFULFILLED,
                (notCollected ? "Not collected: " : "Seller cannot fulfil: ") + reason, caller, p -> {
                    p.setUnfulfilledAt(Instant.now());
                    p.setUnfulfilledReason(reason);
                    p.setUnfulfilledBy(UnfulfilledBy.SELLER);
                });
        stockReturner.returnOnce(parcel);
        fulfilmentRepository.save(parcel);

        long refundCents = turnTheMoneyAround(parcel, reason);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderId", parcel.getOrderId().toString());
        metadata.put("orderRef", order.getOrderRef());
        metadata.put("merchantId", parcel.getMerchantId().toString());
        metadata.put("refundDueCents", refundCents);
        metadata.put("notCollected", notCollected);
        auditService.record(AuditEventType.FULFILMENT_UNFULFILLED, caller.uuid(),
                parcel.getId().toString(), metadata);
        metrics.fulfilmentOutcome("unfulfilled", 1);
        // Told AFTER commit: the buyer must never hear that their goods are not
        // coming because of a transaction that then rolled back.
        eventPublisher.publishEvent(new ParcelUnfulfilled(order.getId(), order.getOrderRef(),
                order.getBuyerMsisdn(), reason, refundCents, order.getCurrency(), notCollected,
                parcel.getId()));
        return toMerchantView(parcel);
    }

    /**
     * The BUYER calls off a paid parcel the seller has not sent yet (V16).
     *
     * <p>The same three things happen as when a seller declines — the parcel
     * ends, its units go back on the shelf, the money turns around onto the
     * refund queue — in one transaction, and the parcel records that it was
     * the buyer ({@link UnfulfilledBy#BUYER}) so no screen blames the seller.
     *
     * <p><b>PREPARING only.</b> Once a parcel is DISPATCHED the goods are with a
     * courier or set aside at the counter, and changing one's mind is a
     * conversation with the seller, not a button.
     *
     * <p><b>Only while the money is still HELD, checked BEFORE anything
     * moves.</b> A seller's decline may close a parcel whose money is already
     * elsewhere, because refusing it would leave the parcel open. A buyer's
     * cancel has no such need, and letting it through would promise a refund
     * the ledger then does not queue: a DISPUTED parcel belongs to the
     * operator looking at it (409 {@code parcel_disputed}); anything else is
     * past the point a cancel means anything (409 {@code parcel_not_cancellable}).
     *
     * <p>The seller is alerted after commit; the buyer is not — they did it
     * themselves, on the screen in front of them. They hear again when the
     * operator actually sends the refund.
     */
    @Transactional
    public OrderFulfilment cancelByBuyer(AuthenticatedUser buyer, UUID fulfilmentId,
                                         String rawReason) {
        OrderFulfilment parcel = fulfilmentRepository.findById(fulfilmentId)
                .orElseThrow(FulfilmentService::notFound);
        MarketOrder order = requireOrder(parcel);
        // Owner-masked, as in confirmReceived: someone else's parcel and a
        // nonexistent one are the same 404.
        if (!order.getBuyerUuid().equals(UUID.fromString(buyer.uuid()))) {
            throw notFound();
        }
        if (parcel.getStatus() != FulfilmentStatus.PREPARING) {
            throw ApiException.conflict("parcel_not_cancellable", parcel.getStatus()
                    == FulfilmentStatus.DISPATCHED
                    ? "The seller has already sent this parcel - contact them, or open a dispute "
                            + "if it does not arrive"
                    : "This parcel is " + parcel.getStatus() + " and can no longer be cancelled");
        }
        MerchantSettlement settlement = settlementService.forParcel(parcel.getId());
        if (settlement != null && settlement.getStatus() == SettlementStatus.DISPUTED) {
            throw ApiException.conflict("parcel_disputed",
                    "This parcel is under dispute - our support team will settle it");
        }
        if (settlement == null || settlement.getStatus() != SettlementStatus.HELD) {
            // No row is a pre-V10 parcel: nothing recorded to turn around, so
            // cancelling would promise a refund the ledger cannot queue.
            throw ApiException.conflict("parcel_not_cancellable",
                    "This parcel can no longer be cancelled - contact support");
        }
        String reason = blankToNull(TextSanitizer.sanitize(rawReason));
        transition(parcel, order.getDeliveryMethod(), FulfilmentStatus.UNFULFILLED,
                reason == null ? "Cancelled by buyer" : "Cancelled by buyer: " + reason, buyer,
                p -> {
                    p.setUnfulfilledAt(Instant.now());
                    p.setUnfulfilledReason(reason);
                    p.setUnfulfilledBy(UnfulfilledBy.BUYER);
                });
        stockReturner.returnOnce(parcel);
        fulfilmentRepository.save(parcel);
        settlementService.markRefundDueCancelledByBuyer(settlement);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderId", parcel.getOrderId().toString());
        metadata.put("orderRef", order.getOrderRef());
        metadata.put("merchantId", parcel.getMerchantId().toString());
        metadata.put("refundDueCents", settlement.getGrossCents());
        metadata.put("cancelledByBuyer", true);
        auditService.record(AuditEventType.FULFILMENT_UNFULFILLED, buyer.uuid(),
                parcel.getId().toString(), metadata);
        metrics.fulfilmentOutcome("cancelled_by_buyer", 1);
        eventPublisher.publishEvent(new ParcelCancelledByBuyer(parcel.getMerchantId(),
                order.getOrderRef(), parcel.getId(), reason));
        return parcel;
    }

    /**
     * Puts this parcel's money on the refund queue — when it is still the
     * platform's to turn around.
     *
     * <p>A settlement already DISPUTED belongs to an operator who is looking at
     * it, and one already released or paid is past the point a seller can hand
     * back. Neither is an error here: the seller's statement is true and worth
     * recording either way, and refusing the decline would leave the parcel
     * open — which is the exact state this method exists to end. So the money
     * stays where it is and the decision stays with whoever owns it.
     *
     * @return the cents queued for refund, 0 when nothing moved. GROSS, not
     *         net: the buyer is owed everything they paid for this parcel —
     *         goods and delivery — and a commission the platform would have
     *         taken on a sale that never happened is not theirs to lose.
     */
    private long turnTheMoneyAround(OrderFulfilment parcel, String reason) {
        MerchantSettlement settlement = settlementService.forParcel(parcel.getId());
        if (settlement == null) {
            // Pre-V10 parcels have no settlement row; log loudly rather than
            // invent money that was never recorded.
            log.error("Parcel declared unfulfillable with NO settlement row id={} orderId={}",
                    parcel.getId(), parcel.getOrderId());
            return 0;
        }
        if (settlement.getStatus() != SettlementStatus.HELD) {
            log.warn("Parcel unfulfillable but settlement is {} - money left where it is "
                            + "id={} orderId={}",
                    settlement.getStatus(), parcel.getId(), parcel.getOrderId());
            return 0;
        }
        settlementService.markRefundDue(settlement, reason);
        return settlement.getGrossCents();
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
        return new CollectCodeResponse(parcel.getId(), code, grouped, now, sentTo,
                collectionPoints.snapshotFor(order.getId(), parcel.getMerchantId()));
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
        MarketOrder order = requireOrder(parcel);
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

        close(parcel, order, DeliveryConfirmer.RECIPIENT, "Collected - handover code redeemed",
                caller, p -> p.setCollectCodeRedeemedAt(Instant.now()));
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

    private void close(OrderFulfilment parcel, MarketOrder order, DeliveryConfirmer by,
                       String detail, AuthenticatedUser actor) {
        close(parcel, order, by, detail, actor, p -> { });
    }

    private void close(OrderFulfilment parcel, MarketOrder order, DeliveryConfirmer by,
                       String detail, AuthenticatedUser actor,
                       java.util.function.Consumer<OrderFulfilment> extra) {
        transition(parcel, order.getDeliveryMethod(), FulfilmentStatus.DELIVERED, detail, actor, p -> {
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
    private void transition(OrderFulfilment parcel, DeliveryMethod method, FulfilmentStatus to,
                            String detail, AuthenticatedUser actor,
                            java.util.function.Consumer<OrderFulfilment> mutation) {
        FulfilmentStatus from = parcel.getStatus();
        if (!FulfilmentStateMachine.isLegal(from, to, method)) {
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

    private MarketOrder requireOrder(OrderFulfilment parcel) {
        return orderRepository.findById(parcel.getOrderId())
                .orElseThrow(FulfilmentService::notFound);
    }

    /**
     * Tells the buyer, after commit, that the seller moved their parcel. Only
     * for the seller's moves: a buyer's own confirmation or a redeemed code is
     * something the person on the other end already knows.
     */
    private void publishProgress(MarketOrder order, OrderFulfilment parcel) {
        boolean partOfOrder = fulfilmentRepository
                .findByOrderIdOrderByCreatedAtAsc(order.getId()).size() > 1;
        eventPublisher.publishEvent(new ParcelProgressed(order.getId(), order.getOrderRef(),
                order.getBuyerMsisdn(), order.getDeliveryMethod(), parcel.getStatus(),
                partOfOrder, parcel.getId()));
    }

    private MerchantFulfilmentResponse toMerchantView(OrderFulfilment parcel) {
        return parcelViews.toView(parcel);
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
