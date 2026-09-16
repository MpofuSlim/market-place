package com.innbucks.marketplaceservice.settlement;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.fulfilment.DeliveryConfirmer;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderEvent;
import com.innbucks.marketplaceservice.order.MarketOrderEventRepository;
import com.innbucks.marketplaceservice.order.MarketOrderItem;
import com.innbucks.marketplaceservice.order.MarketOrderItemRepository;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The escrow ledger's engine: opens a settlement per parcel when the money
 * arrives, releases it when the goods do, and refuses everything the state
 * machine does not allow.
 *
 * <p><b>What "escrow" means here.</b> payment-service already collected the
 * buyer's money into the shared platform account (settlement tag {@code MKT});
 * this service does not move a cent. It is the LEDGER that decides when the
 * operator may pay a seller — which is the half that turns "we took your
 * money" into "the seller only gets paid when you get your goods". Actual
 * transfers (payouts, refunds) are operator actions on the rails, recorded
 * here with their references; the InnBucks code rail has no reversal API, so
 * pretending this service could execute a refund would be a lie in code.
 *
 * <p><b>Release policy</b> — the two edges out of HELD:
 * <ul>
 *   <li>Buyer confirms receipt → RELEASABLE immediately. The buyer's own
 *       word is the strongest evidence the platform holds; making them ALSO
 *       wait out a grace window would punish exactly the confirmation the
 *       product wants to encourage.</li>
 *   <li>Seller closes the parcel themselves → HELD for a grace window
 *       ({@code marketplace.settlement.grace-hours}, default 48) that is the
 *       buyer's chance to object, then released by the sweeper. The window
 *       prices the weaker evidence: a seller's own say-so buys them their
 *       money two days later, not never.</li>
 * </ul>
 *
 * <p><b>Every status change goes through {@link #transition}</b> — legality,
 * mutation, the order-journal row (kind SETTLEMENT) and the metric in one
 * chokepoint, validating BEFORE mutating like the fulfilment machine. Audit
 * policy deliberately sits at the DECISION points instead (dispute, resolve,
 * payout run): a mechanical release is already evidenced by the
 * FULFILMENT_DELIVERED audit it follows, and auditing each row of a 500-row
 * sweep would serialise the sweep on the audit chain head's row lock.
 */
@Slf4j
@Service
public class SettlementService {

    private final MerchantSettlementRepository settlementRepository;
    private final OrderFulfilmentRepository fulfilmentRepository;
    private final MarketOrderItemRepository itemRepository;
    private final MarketOrderEventRepository eventRepository;
    private final AuditService auditService;
    private final MarketplaceMetrics metrics;
    private final Duration grace;
    private final double commissionPercent;

    public SettlementService(MerchantSettlementRepository settlementRepository,
                             OrderFulfilmentRepository fulfilmentRepository,
                             MarketOrderItemRepository itemRepository,
                             MarketOrderEventRepository eventRepository,
                             AuditService auditService,
                             MarketplaceMetrics metrics,
                             @Value("${marketplace.settlement.grace-hours}") long graceHours,
                             @Value("${marketplace.settlement.commission-percent}") double commissionPercent) {
        this.settlementRepository = settlementRepository;
        this.fulfilmentRepository = fulfilmentRepository;
        this.itemRepository = itemRepository;
        this.eventRepository = eventRepository;
        this.auditService = auditService;
        this.metrics = metrics;
        this.grace = Duration.ofHours(graceHours);
        this.commissionPercent = commissionPercent;
    }

    // ------------------------------------------------------------------
    // Opening — driven by the PAID transition, beside the parcels
    // ------------------------------------------------------------------

    /**
     * Opens one HELD settlement per parcel of a just-paid order.
     *
     * <p>{@code MANDATORY} propagation for the same reason the parcels
     * themselves are: money recorded as collected with no ledger row saying
     * which seller it belongs to is exactly the state this table exists to
     * make impossible. Idempotent by the fulfilment unique index, so a
     * replayed payment confirm cannot double a seller's money.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void openForOrder(MarketOrder order) {
        Map<UUID, Long> grossByMerchant = new LinkedHashMap<>();
        for (MarketOrderItem item : itemRepository.findByOrderId(order.getId())) {
            grossByMerchant.merge(item.getMerchantId(), item.getLineTotalCents(), Long::sum);
        }
        Instant now = Instant.now();
        int opened = 0;
        for (OrderFulfilment parcel
                : fulfilmentRepository.findByOrderIdOrderByCreatedAtAsc(order.getId())) {
            Long gross = grossByMerchant.get(parcel.getMerchantId());
            if (gross == null || gross <= 0) {
                // Cannot happen for a parcel opened from these same items; a
                // row that somehow disagrees is logged, never invented.
                log.error("No line total for parcel id={} merchantId={} orderRef={} — "
                        + "settlement NOT opened", parcel.getId(), parcel.getMerchantId(),
                        order.getOrderRef());
                continue;
            }
            long commission = Math.round(gross * commissionPercent / 100.0);
            opened += settlementRepository.openIfAbsent(UUID.randomUUID(), order.getId(),
                    parcel.getId(), parcel.getMerchantId(), gross, commission,
                    gross - commission, order.getCurrency(), now);
        }
        if (opened > 0) {
            metrics.settlementOutcome("opened", opened);
            journal(order.getId(), null, SettlementStatus.HELD,
                    opened + " settlement(s) opened - money held until delivery");
        }
    }

    // ------------------------------------------------------------------
    // Release — driven by the parcel's DELIVERED transition
    // ------------------------------------------------------------------

    /**
     * Called by the fulfilment machine IN the delivering transaction. Buyer
     * confirmation releases now; a seller's own closure starts the grace
     * clock for the sweeper. A DISPUTED settlement is left exactly where it
     * is — delivery does not settle an argument.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onParcelDelivered(OrderFulfilment parcel) {
        MerchantSettlement settlement = settlementRepository
                .findByFulfilmentId(parcel.getId()).orElse(null);
        if (settlement == null) {
            // Pre-V10 parcels delivered before their backfilled settlement
            // existed can race the deploy; log loudly rather than invent money.
            log.error("Parcel delivered with NO settlement row id={} orderId={} — check V10 backfill",
                    parcel.getId(), parcel.getOrderId());
            return;
        }
        if (settlement.getStatus() != SettlementStatus.HELD) {
            return; // disputed, or already released by an operator decision
        }
        if (parcel.getDeliveredBy() == DeliveryConfirmer.BUYER) {
            transition(settlement, SettlementStatus.RELEASABLE,
                    "Released - buyer confirmed receipt", s -> {
                        s.setReleasedAt(Instant.now());
                        s.setReleasableAt(null);
                    });
            metricRelease(1);
        } else {
            Instant releasableAt = Instant.now().plus(grace);
            settlement.setReleasableAt(releasableAt);
            settlement.setUpdatedAt(Instant.now());
            settlementRepository.save(settlement);
            journal(settlement.getOrderId(), SettlementStatus.HELD, SettlementStatus.HELD,
                    "Seller marked delivered - grace until " + releasableAt);
        }
    }

    /**
     * Releases ONE lapsed-grace settlement in its own transaction (sweeper
     * path — the expiry sweeper's per-row shape). Re-checks state after the
     * sweep query; a dispute that landed in between simply wins the race.
     */
    @Transactional
    public boolean releaseOne(UUID settlementId) {
        MerchantSettlement settlement = settlementRepository.findById(settlementId).orElse(null);
        if (settlement == null
                || settlement.getStatus() != SettlementStatus.HELD
                || settlement.getReleasableAt() == null
                || settlement.getReleasableAt().isAfter(Instant.now())) {
            return false;
        }
        transition(settlement, SettlementStatus.RELEASABLE,
                "Released - grace window lapsed unchallenged", s -> {
                    s.setReleasedAt(Instant.now());
                    s.setReleasableAt(null);
                });
        metricRelease(1);
        return true;
    }

    // ------------------------------------------------------------------
    // Dispute freeze / resolution (called by DisputeService, same tx)
    // ------------------------------------------------------------------

    @Transactional(propagation = Propagation.MANDATORY)
    void freeze(MerchantSettlement settlement, DisputeReason reason) {
        transition(settlement, SettlementStatus.DISPUTED,
                "Frozen - buyer disputed (" + reason + ")", s -> s.setReleasableAt(null));
        metrics.settlementOutcome("disputed", 1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void releaseByOperator(MerchantSettlement settlement) {
        transition(settlement, SettlementStatus.RELEASABLE,
                "Released - operator resolved the dispute for the seller",
                s -> s.setReleasedAt(Instant.now()));
        metricRelease(1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void recordRefund(MerchantSettlement settlement, String refundReference) {
        transition(settlement, SettlementStatus.REFUNDED,
                "Refund recorded - operator resolved the dispute for the buyer", s -> {
                    s.setRefundedAt(Instant.now());
                    s.setRefundReference(refundReference);
                });
        metrics.settlementOutcome("refund_recorded", 1);
    }

    // ------------------------------------------------------------------
    // Payout — the operator's run
    // ------------------------------------------------------------------

    /**
     * Marks EVERY RELEASABLE settlement of one merchant paid, under one
     * payout reference — the shape finance actually pays in: one transfer per
     * merchant covering everything cleared, not a click per parcel.
     *
     * <p>Each row still goes through the chokepoint (its order's journal gets
     * the row-level trail), but the AUDIT is one event per run: one operator
     * decision, one tamper-evident record, carrying the merchant, count,
     * total and reference. Refuses an empty run — "paid nothing" under a real
     * bank reference would be a ledger entry describing no money.
     */
    @Transactional
    public PayoutOutcome payOutReleasable(AuthenticatedUser operator, UUID merchantId,
                                          String payoutReference) {
        List<MerchantSettlement> releasable = settlementRepository
                .findByMerchantIdAndStatus(merchantId, SettlementStatus.RELEASABLE);
        if (releasable.isEmpty()) {
            throw ApiException.conflict("nothing_releasable",
                    "This merchant has no releasable settlements to pay out");
        }
        Instant now = Instant.now();
        long total = 0;
        for (MerchantSettlement settlement : releasable) {
            transition(settlement, SettlementStatus.PAID_OUT,
                    "Paid out - " + payoutReference, s -> {
                        s.setPaidOutAt(now);
                        s.setPayoutReference(payoutReference);
                    });
            total += settlement.getNetCents();
        }
        metrics.settlementOutcome("paid_out", releasable.size());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("merchantId", merchantId.toString());
        metadata.put("parcels", releasable.size());
        metadata.put("totalNetCents", total);
        metadata.put("payoutReference", payoutReference);
        auditService.record(AuditEventType.SETTLEMENT_PAID_OUT, operator.uuid(),
                merchantId.toString(), metadata);
        log.info("payout run merchantId={} parcels={} totalNetCents={} ref={}",
                merchantId, releasable.size(), total, payoutReference);
        return new PayoutOutcome(releasable.size(), total,
                releasable.getFirst().getCurrency());
    }

    /** What one payout run did. */
    public record PayoutOutcome(int parcels, long totalNetCents, String currency) {
    }

    // ------------------------------------------------------------------
    // Chokepoint
    // ------------------------------------------------------------------

    /**
     * THE single chokepoint: validate against the state machine, mutate only
     * after legality (structural, per the fulfilment machine's lesson),
     * journal into the ORDER's history with kind SETTLEMENT, count.
     */
    private void transition(MerchantSettlement settlement, SettlementStatus to, String detail,
                            Consumer<MerchantSettlement> mutation) {
        SettlementStatus from = settlement.getStatus();
        if (!SettlementStateMachine.isLegal(from, to)) {
            metrics.settlementOutcome("illegal_transition", 1);
            log.warn("Illegal settlement transition refused id={} orderId={} {} -> {}",
                    settlement.getId(), settlement.getOrderId(), from, to);
            throw ApiException.conflict("illegal_settlement_state",
                    "This settlement is " + from + " and cannot move to " + to);
        }
        mutation.accept(settlement);
        settlement.setStatus(to);
        settlement.setUpdatedAt(Instant.now());
        settlementRepository.save(settlement);
        journal(settlement.getOrderId(), from, to, detail);
        log.info("settlement {} -> {} id={} orderId={} merchantId={} netCents={}",
                from, to, settlement.getId(), settlement.getOrderId(),
                settlement.getMerchantId(), settlement.getNetCents());
    }

    private void metricRelease(int count) {
        metrics.settlementOutcome("released", count);
    }

    private void journal(UUID orderId, SettlementStatus from, SettlementStatus to, String detail) {
        eventRepository.save(MarketOrderEvent.builder()
                .orderId(orderId)
                .kind(MarketOrderEvent.KIND_SETTLEMENT)
                .fromStatus(from == null ? null : from.name())
                .toStatus(to.name())
                .detail(detail != null && detail.length() > 255 ? detail.substring(0, 255) : detail)
                .createdAt(Instant.now())
                .build());
    }

    /** Convenience for view assembly: this parcel's settlement, if opened. */
    @Transactional(readOnly = true)
    public MerchantSettlement forParcel(UUID fulfilmentId) {
        return settlementRepository.findByFulfilmentId(fulfilmentId).orElse(null);
    }
}
