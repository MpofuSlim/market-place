package com.innbucks.marketplaceservice.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business-level metrics for the marketplace domain. Spring Boot Actuator
 * already surfaces HTTP latency per endpoint at the controller level; these add
 * the marketplace-specific signals so dashboards split order failures by cause
 * and the security alerts (audit tamper/chain) have a series to fire on.
 *
 * <p>All names use the {@code marketplace.} prefix so they're isolated from the
 * other fleet services' series in /actuator/prometheus.
 */
@Component
public class MarketplaceMetrics {

    private final MeterRegistry registry;
    private final Counter listingsCreated;
    private final Counter confirmMismatch;
    private final Counter illegalTransitions;
    private final Counter internalTokenRejected;
    private final Counter expiryReleased;
    private final Counter auditIntegrityBroken;
    private final Counter auditChainBroken;

    public MarketplaceMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.listingsCreated = Counter.builder("marketplace.listings.created")
                .description("Merchant listings created")
                .register(registry);
        // The 100x guard tripped: payments confirmed an amount that does not
        // equal the order total. The order parks unconfirmed — ANY non-zero
        // value is a money incident, page on it.
        this.confirmMismatch = Counter.builder("marketplace.orders.confirm_mismatch")
                .description("Payment confirmations rejected because the paid amount did not match the order total")
                .register(registry);
        // Refused order state-machine transitions (e.g. paying a CANCELLED
        // order). Refused + counted, never applied — a steady drip means a
        // caller is racing the expiry sweeper or replaying stale requests.
        this.illegalTransitions = Counter.builder("marketplace.orders.illegal_transitions")
                .description("Order state transitions refused as illegal by the lifecycle map")
                .register(registry);
        // The S2S trust boundary was probed or a caller is misconfigured —
        // pairs with the INTERNAL_TOKEN_REJECTED audit event. Same series
        // InternalTokenAuthorizer increments (it does its own counting); this
        // helper exists for any future rejection path outside the authorizer —
        // keep ONE series name so dashboards/alerts never fork.
        this.internalTokenRejected = Counter.builder("marketplace.internal.token.rejected")
                .description("X-Internal-Token validation failures on /marketplace/internal/** endpoints")
                .register(registry);
        this.expiryReleased = Counter.builder("marketplace.orders.expiry_released")
                .description("PENDING_PAYMENT orders lapsed by the expiry sweep (stock returned)")
                .baseUnit("orders")
                .register(registry);
        // OWASP A09 tamper signal from the audit-log HMAC verifier. The invariant
        // is zero, so ANY increase is page-worthy — someone altered an
        // audit_events row, or the audit hmac-secret rotated without a re-seal.
        this.auditIntegrityBroken = Counter.builder("marketplace.audit.integrity.broken")
                .description("audit_events rows whose stored HMAC failed verification (tamper signal)")
                .baseUnit("rows")
                .register(registry);
        // Hash-chain break: a row was DELETED, REORDERED, or the tail truncated —
        // the chain link no longer recomputes. Ticket severity (not page like
        // content-tamper above): the surviving rows' content is still intact and
        // a break can also be a benign secret rotation without a re-chain.
        this.auditChainBroken = Counter.builder("marketplace.audit.chain.broken")
                .description("audit_events chain links that failed to recompute (deletion/reorder signal)")
                .baseUnit("rows")
                .register(registry);
    }

    public void listingCreated() {
        listingsCreated.increment();
    }

    /**
     * Counter for order outcomes. Tagged so dashboards split
     * outcome={created, paid, cancelled, expired, ...} on one series; the
     * outcome vocabulary is owned by the order domain — keep the cardinality
     * to lifecycle outcomes, never per-order values.
     */
    public void orderOutcome(String outcome) {
        Counter.builder("marketplace.orders")
                .description("Marketplace orders by lifecycle outcome")
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment();
    }

    public void confirmMismatch() {
        confirmMismatch.increment();
    }

    public void illegalTransition() {
        illegalTransitions.increment();
    }

    public void internalTokenRejected() {
        internalTokenRejected.increment();
    }

    /** Called once per expiry-sweep pass with the number of orders it lapsed. */
    public void expirySweep(int released) {
        if (released > 0) expiryReleased.increment(released);
    }

    /** Called by the audit-integrity verifier with the count of rows that failed
     *  HMAC checking. Page severity — the invariant is zero. */
    public void auditIntegrityBroken(long count) {
        if (count > 0) auditIntegrityBroken.increment(count);
    }

    /** Called by the audit-integrity verifier with the count of broken hash-chain
     *  links (a deleted/reordered/truncated row). Ticket severity — see the
     *  counter's description in the constructor. */
    public void auditChainBroken(long count) {
        if (count > 0) auditChainBroken.increment(count);
    }
}
