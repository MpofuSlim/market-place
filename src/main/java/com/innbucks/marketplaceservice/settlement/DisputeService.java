package com.innbucks.marketplaceservice.settlement;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.fulfilment.BuyerParcelRules;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilment;
import com.innbucks.marketplaceservice.fulfilment.OrderFulfilmentRepository;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import com.innbucks.marketplaceservice.order.MarketOrder;
import com.innbucks.marketplaceservice.order.MarketOrderRepository;
import com.innbucks.marketplaceservice.security.AuthenticatedUser;
import com.innbucks.marketplaceservice.settlement.dto.DisputeResponse;
import com.innbucks.marketplaceservice.settlement.dto.ResolveDisputeRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Disputes: the buyer's half of the escrow.
 *
 * <p>A dispute is the ONLY way a buyer stops a seller being paid, and the only
 * refund path the platform has — which is also why disputing an UNDELIVERED
 * parcel is legal: "it never arrived" and "the seller can't fulfil this" are
 * precisely the cases that need the money frozen. The operator resolves each
 * one exactly once: RELEASE (seller was right — money clears) or REFUND
 * (buyer was right — refund recorded here, EXECUTED by the operator on the
 * rails, which have no reversal API this service could call without lying).
 *
 * <p>Windows: an undelivered parcel is disputable for as long as the money
 * has not been paid out; a DELIVERED parcel for
 * {@code marketplace.settlement.dispute-window-days} (default 7) after
 * delivery. After payout, nothing is disputable — the money has left, and a
 * ledger that pretended otherwise would be describing transfers it cannot
 * make.
 */
@Slf4j
@Service
public class DisputeService {

    /** Same hard page cap as every other queue. */
    static final int MAX_PAGE_SIZE = 50;

    private final SettlementDisputeRepository disputeRepository;
    private final MerchantSettlementRepository settlementRepository;
    private final OrderFulfilmentRepository fulfilmentRepository;
    private final MarketOrderRepository orderRepository;
    private final SettlementService settlementService;
    private final AuditService auditService;
    private final MarketplaceMetrics metrics;
    private final ApplicationEventPublisher eventPublisher;
    private final BuyerParcelRules buyerRules;

    public DisputeService(SettlementDisputeRepository disputeRepository,
                          MerchantSettlementRepository settlementRepository,
                          OrderFulfilmentRepository fulfilmentRepository,
                          MarketOrderRepository orderRepository,
                          SettlementService settlementService,
                          AuditService auditService,
                          MarketplaceMetrics metrics,
                          ApplicationEventPublisher eventPublisher,
                          BuyerParcelRules buyerRules) {
        this.disputeRepository = disputeRepository;
        this.settlementRepository = settlementRepository;
        this.fulfilmentRepository = fulfilmentRepository;
        this.orderRepository = orderRepository;
        this.settlementService = settlementService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.eventPublisher = eventPublisher;
        this.buyerRules = buyerRules;
    }

    // ------------------------------------------------------------------
    // Buyer opens
    // ------------------------------------------------------------------

    /**
     * Opens the dispute and freezes the parcel's settlement, atomically.
     * Owner-masked exactly like receipt confirmation: someone else's parcel
     * is the same 404 as a nonexistent one.
     */
    @Transactional
    public DisputeResponse open(AuthenticatedUser buyer, UUID orderId, UUID fulfilmentId,
                                DisputeReason reason, String detail) {
        MarketOrder order = orderRepository.findByIdAndBuyerUuid(orderId,
                        UUID.fromString(buyer.uuid()))
                .orElseThrow(() -> ApiException.notFound("order_not_found", "Order not found"));
        OrderFulfilment parcel = fulfilmentRepository.findById(fulfilmentId)
                .filter(p -> p.getOrderId().equals(order.getId()))
                .orElseThrow(() -> ApiException.notFound("fulfilment_not_found",
                        "Fulfilment not found"));
        // The rule the buyer's canDispute flag is computed from (BuyerParcelRules):
        // paid order, a settlement to freeze, money still arguable, inside the
        // window, never disputed before — refused in that order.
        MerchantSettlement settlement = settlementRepository.findByFulfilmentId(parcel.getId())
                .orElse(null);
        boolean alreadyDisputed = disputeRepository.findByFulfilmentId(parcel.getId()).isPresent();
        BuyerParcelRules.Refusal refusal = buyerRules.disputeRefusal(order.getStatus(), parcel,
                settlement, alreadyDisputed, Instant.now());
        if (refusal != null) {
            throw refusal.toException();
        }

        settlementService.freeze(settlement, reason);
        Instant now = Instant.now();
        SettlementDispute dispute = SettlementDispute.builder()
                .id(UUID.randomUUID())
                .settlementId(settlement.getId())
                .fulfilmentId(parcel.getId())
                .orderId(order.getId())
                .merchantId(parcel.getMerchantId())
                .buyerUuid(order.getBuyerUuid())
                .reason(reason)
                .detail(blankToNull(TextSanitizer.sanitize(detail)))
                .status(DisputeStatus.OPEN)
                .createdAt(now)
                .build();
        disputeRepository.save(dispute);
        metrics.orderOutcome("disputed");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderRef", order.getOrderRef());
        metadata.put("merchantId", parcel.getMerchantId().toString());
        // The bounded reason only — the buyer's free text never enters the
        // tamper-evident trail (V7 report stance).
        metadata.put("reason", reason.name());
        metadata.put("netCents", settlement.getNetCents());
        auditService.record(AuditEventType.SETTLEMENT_DISPUTED, buyer.uuid(),
                dispute.getId().toString(), metadata);
        // The seller hears about it after commit: their money just froze.
        eventPublisher.publishEvent(new DisputeOpened(parcel.getMerchantId(),
                order.getOrderRef(), parcel.getId(), reason));
        log.info("dispute opened id={} orderRef={} parcel={} reason={}",
                dispute.getId(), order.getOrderRef(), parcel.getId(), reason);
        return DisputeResponse.from(dispute, settlement);
    }

    // ------------------------------------------------------------------
    // Operator queue + resolution
    // ------------------------------------------------------------------

    /** The queue: one status (default OPEN), OLDEST first — FIFO, so the
     *  buyer who has waited longest is served first. */
    @Transactional(readOnly = true)
    public Page<DisputeResponse> queue(DisputeStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        Page<SettlementDispute> result = status == null
                ? disputeRepository.findAllByOrderByCreatedAtAsc(pageable)
                : disputeRepository.findByStatusOrderByCreatedAtAsc(status, pageable);
        // ONE grouped read for the page's settlements (the assembler
        // discipline), so every row can carry the money at stake without a
        // per-row query.
        Map<UUID, MerchantSettlement> settlements = settlementRepository
                .findAllById(result.getContent().stream()
                        .map(SettlementDispute::getSettlementId).toList())
                .stream()
                .collect(Collectors.toMap(MerchantSettlement::getId, Function.identity()));
        return result.map(d -> DisputeResponse.from(d, settlements.get(d.getSettlementId())));
    }

    /**
     * Resolves ONE dispute, exactly once. RELEASE moves the money back to
     * RELEASABLE (the seller's payout run picks it up); REFUND records the
     * refund with the operator's reference — the transfer itself is theirs to
     * execute on the rails. Either way the buyer is told, after commit,
     * best-effort ({@link DisputeResolved}).
     */
    @Transactional
    public DisputeResponse resolve(AuthenticatedUser operator, UUID disputeId,
                                   ResolveDisputeRequest request) {
        SettlementDispute dispute = disputeRepository.findById(disputeId)
                .orElseThrow(() -> ApiException.notFound("dispute_not_found", "Dispute not found"));
        if (dispute.getStatus() != DisputeStatus.OPEN) {
            throw ApiException.conflict("dispute_not_open",
                    "This dispute was already resolved (" + dispute.getStatus() + ")");
        }
        MerchantSettlement settlement = settlementRepository.findById(dispute.getSettlementId())
                .orElseThrow(() -> new IllegalStateException(
                        "Dispute " + disputeId + " references a missing settlement"));

        boolean refund = request.action() == ResolveDisputeRequest.Action.REFUND;
        if (refund) {
            settlementService.recordRefund(settlement,
                    blankToNull(TextSanitizer.sanitize(request.refundReference())));
            dispute.setStatus(DisputeStatus.REFUNDED);
        } else {
            settlementService.releaseByOperator(settlement);
            dispute.setStatus(DisputeStatus.RELEASED);
        }
        dispute.setResolutionNote(blankToNull(TextSanitizer.sanitize(request.resolutionNote())));
        dispute.setResolvedBy(UUID.fromString(operator.uuid()));
        dispute.setResolvedAt(Instant.now());
        disputeRepository.save(dispute);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("action", request.action().name());
        metadata.put("merchantId", dispute.getMerchantId().toString());
        metadata.put("netCents", settlement.getNetCents());
        if (settlement.getRefundReference() != null) {
            metadata.put("refundReference", settlement.getRefundReference());
        }
        auditService.record(AuditEventType.SETTLEMENT_DISPUTE_RESOLVED, operator.uuid(),
                dispute.getId().toString(), metadata);
        // Published IN the transaction; the AFTER_COMMIT listener tells the
        // buyer only if this resolution actually commits.
        String orderRef = orderRepository.findById(dispute.getOrderId())
                .map(MarketOrder::getOrderRef).orElse(null);
        eventPublisher.publishEvent(DisputeResolved.of(dispute, settlement, orderRef));
        log.info("dispute resolved id={} action={} orderId={}",
                dispute.getId(), request.action(), dispute.getOrderId());
        return DisputeResponse.from(dispute, settlement);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
