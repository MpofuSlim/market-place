package com.innbucks.marketplaceservice.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

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
    private final Counter restockEvents;
    private final Counter auditIntegrityBroken;
    private final Counter auditChainBroken;
    private final AtomicLong staleSettlements = new AtomicLong();
    private final AtomicLong stockDrift = new AtomicLong();

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
        // Restock-alert FOUNDATION: a listing's stock moved 0 -> >0 (merchant
        // update or an order release returning the last held units). Today the
        // AFTER_COMMIT listener only logs the favoriter count; when the
        // notification wiring lands, this series becomes its send-volume input.
        this.restockEvents = Counter.builder("marketplace.restock_events")
                .description("Listings whose stock moved from 0 to >0 (back-in-stock signal)")
                .baseUnit("listings")
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
        // Registered once at construction: the gauge READS the AtomicLong the
        // stale sweep writes, so the series exists (and reports 0) from boot
        // rather than appearing only after the first sweep finds something —
        // an alert cannot fire on a series that is not there yet.
        Gauge.builder("marketplace.settlements.stale", staleSettlements, AtomicLong::doubleValue)
                .description("Settlements HELD past the staleness threshold - money no timer can release")
                .baseUnit("settlements")
                .register(registry);
        // V19, same reasoning: the drift sweep writes it, and it reads 0 from
        // boot so an alert on "> 0" has a series to watch from the start.
        Gauge.builder("marketplace.stock.aggregate_drift", stockDrift, AtomicLong::doubleValue)
                .description("Variant listings whose stock total disagrees with their options")
                .baseUnit("listings")
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

    /**
     * Review write outcomes on one tagged series:
     * outcome={created, rejected_unverified, duplicate}. rejected_unverified
     * rising means buyers are trying to review products they never bought —
     * either FE confusion or someone probing the verified-purchase gate.
     */
    public void reviewOutcome(String outcome) {
        Counter.builder("marketplace.reviews")
                .description("Listing review submissions by outcome")
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment();
    }

    /**
     * Calls refused by the {@code /marketplace/public/**} test surface's
     * {@code x-api-key} gate: reason={missing_key, bad_key}. Both answer the
     * caller with the same opaque 401 — the split lives here, where an operator
     * can tell "our own app forgot the header" from "somebody is guessing".
     *
     * <p>Any traffic at all on a cell that should not have the surface on is
     * itself the finding: alert on the sibling {@code marketplace.public_test}
     * counter being non-zero in production, not on this one.
     */
    public void publicTestRejected(String reason) {
        Counter.builder("marketplace.public_test.rejected")
                .description("Public test-surface calls refused by the api-key gate, by reason")
                .tag("reason", reason == null ? "unknown" : reason)
                .register(registry)
                .increment();
    }

    /**
     * One served call on the public test surface, tagged by operation
     * (cart_read, cart_add, address_create, favorite_add, checkout_quote, ...).
     *
     * <p>Exists so "is anyone actually using this?" and "is this switched on
     * somewhere it should not be?" are both answerable from metrics rather than
     * by grepping logs. A non-zero value on a production cell is an incident.
     */
    public void publicTestCall(String operation) {
        Counter.builder("marketplace.public_test")
                .description("Calls served by the public test surface, by operation")
                .tag("operation", operation == null ? "unknown" : operation)
                .register(registry)
                .increment();
    }

    /** Listing reports by reason (bounded enum vocabulary — never free text,
     *  which would explode cardinality). */
    public void reportCreated(String reason) {
        Counter.builder("marketplace.reports")
                .description("Listing reports filed, by reason")
                .tag("reason", reason == null ? "unknown" : reason)
                .register(registry)
                .increment();
    }

    /** Moderation-queue closures by action (RESOLVE/DISMISS). */
    public void reportResolved(String action) {
        Counter.builder("marketplace.reports.resolved")
                .description("Listing reports closed by moderation, by action")
                .tag("action", action == null ? "unknown" : action)
                .register(registry)
                .increment();
    }

    /** One listing's stock moved 0 -> >0 (fired AFTER the restocking tx
     *  committed — never counts a rolled-back restock). */
    public void restockEvent() {
        restockEvents.increment();
    }

    /**
     * Notification delivery outcomes on one tagged series,
     * {@code marketplace.notifications{type,outcome}}. Types are trigger names
     * ({@code order_paid}, {@code restock_alert}, {@code merchant_order},
     * {@code user_notify}); outcomes are the bounded vocabulary
     * {@code sent|fallback|failed|disabled|overflow|accepted|no_recipients} —
     * never per-message values. {@code failed} rising means buyers are moving
     * money blind; {@code overflow} means a restock event exceeded the
     * recipient cap.
     */
    public void notificationOutcome(String type, String outcome) {
        notificationOutcome(type, outcome, 1);
    }

    /** Amount variant — e.g. the restock overflow counts every recipient the
     *  cap skipped, not one per event. */
    public void notificationOutcome(String type, String outcome, double amount) {
        if (amount <= 0) {
            return;
        }
        Counter.builder("marketplace.notifications")
                .description("Marketplace notification sends by trigger type and outcome")
                .tag("type", type == null ? "unknown" : type)
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment(amount);
    }

    /**
     * Fulfilment outcomes on one tagged series,
     * {@code marketplace.fulfilments{outcome}}. The vocabulary is
     * {@code opened|dispatched|delivered|illegal_transition}.
     *
     * <p>The two worth watching: {@code opened} minus {@code delivered} over a
     * window is the backlog sellers owe buyers — the number that says whether
     * paid orders are actually reaching people — and {@code illegal_transition}
     * rising means callers are racing each other (a seller marking delivered a
     * parcel the buyer just confirmed), which is normal in a trickle and a bug
     * in a stream.
     */
    public void fulfilmentOutcome(String outcome, int amount) {
        if (amount <= 0) {
            return;
        }
        Counter.builder("marketplace.fulfilments")
                .description("Order fulfilment parcels by lifecycle outcome")
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment(amount);
    }

    /**
     * Settlement (escrow) outcomes on one tagged series,
     * {@code marketplace.settlements{outcome}}: vocabulary
     * {@code opened|released|disputed|refund_recorded|paid_out|illegal_transition}.
     * {@code disputed} rising is buyers losing trust in sellers;
     * {@code opened} minus {@code released} over a window is money the
     * platform is sitting on.
     */
    public void settlementOutcome(String outcome, int amount) {
        if (amount <= 0) {
            return;
        }
        Counter.builder("marketplace.settlements")
                .description("Merchant settlement parcels by escrow lifecycle outcome")
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment(amount);
    }

    /**
     * Collection-code outcomes, {@code marketplace.collect_codes{outcome}}:
     * vocabulary {@code minted|redeemed|invalid|locked}.
     *
     * <p>{@code invalid} is the one to alert on. A seller mistyping a code the
     * collector read out is ordinary and rare; {@code invalid} climbing on one
     * cell is somebody working through the keyspace of parcels they already
     * hold, trying to buy themselves the instant payout that a real handover
     * earns. {@code locked} means the per-parcel budget actually ran out,
     * which should be close to never.
     */
    /** Courier position pings by outcome: accepted, ignored (too soon / older
     *  than the stored fix / stale), out_of_bounds, not_in_transit. A climb in
     *  out_of_bounds is a broken phone GPS or someone posting made-up points. */
    public void trackingPing(String outcome) {
        Counter.builder("marketplace.tracking.pings")
                .description("Courier position reports by outcome")
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment();
    }

    public void collectCodeOutcome(String outcome) {
        Counter.builder("marketplace.collect_codes")
                .description("Collection handover codes by outcome")
                .tag("outcome", outcome == null ? "unknown" : outcome)
                .register(registry)
                .increment();
    }

    /**
     * How many settlements are sitting HELD past the staleness threshold right
     * now: {@code marketplace.settlements.stale}.
     *
     * <p>A GAUGE and not a counter, because the question an operator has is
     * "how much money is stuck today", not "how many ever were". Every one of
     * these is a buyer who paid, a seller who never delivered and never
     * declined, and nobody watching — the release sweeper cannot see them at
     * all, since a parcel that was never delivered never gets the
     * {@code releasable_at} it matches on. Anything above zero for long is
     * somebody's to chase; a rising floor means sellers are abandoning orders.
     */
    public void staleSettlements(long count) {
        staleSettlements.set(count);
    }

    /**
     * A stock RETURN that could not be credited (V19):
     * {@code marketplace.stock.returns_dropped{reason}}. {@code
     * listing_converted} — the listing switched between plain and variants
     * after the units were reserved, so the old shape's return has nowhere to
     * go and the seller's new counts are the truth; {@code listing_missing} —
     * no such row; {@code variant_removed} — the seller deleted that option;
     * {@code invariant_broken} — a variant listing's total could not be
     * recomputed (should be never). Every lost return is visible here rather
     * than silently absorbed.
     */
    public void stockReturnDropped(String reason) {
        Counter.builder("marketplace.stock.returns_dropped")
                .description("Stock returns that could not be credited back")
                .tag("reason", reason == null ? "unknown" : reason)
                .register(registry)
                .increment();
    }

    /** How many variant listings' totals disagree with their options right now
     *  ({@code marketplace.stock.aggregate_drift}). 0 when healthy; only an
     *  out-of-band write can raise it. */
    public void stockDrift(long count) {
        stockDrift.set(count);
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
