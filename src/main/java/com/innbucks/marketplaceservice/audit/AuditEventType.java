package com.innbucks.marketplaceservice.audit;

/**
 * Enumeration of event types written to {@code audit_events} by
 * marketplace-service (OWASP A09 — tamper-evident trail for listing lifecycle,
 * order money-movement states, and S2S trust-boundary probes).
 *
 * <p>Adding a new value here is the right way to surface a new sensitive
 * action — the underlying column is {@code VARCHAR(64)} to keep this flexible
 * without an enum-table join.
 */
public enum AuditEventType {
    /** A merchant created a listing. */
    LISTING_CREATED,
    /** A merchant updated a listing's content (title/description/price/stock). */
    LISTING_UPDATED,
    /** A listing moved between DRAFT/ACTIVE/INACTIVE/ARCHIVED. */
    LISTING_STATUS_CHANGED,
    /** A buyer created an order — stock was reserved and a payable total minted. */
    ORDER_CREATED,
    /** An order was confirmed as paid by the platform payments service — the
     *  paid amount matched the order total and the order moved to PAID. */
    ORDER_PAID,
    /** An order was cancelled — its stock reservation was released. */
    ORDER_CANCELLED,
    /** A PENDING_PAYMENT order lapsed past its payment TTL and the expiry
     *  sweep released its stock. */
    ORDER_EXPIRED,
    /** The 100x guard tripped: payment confirmation carried an amount that did
     *  not equal the order total. The order parked unconfirmed — any occurrence
     *  is a money incident. */
    ORDER_CONFIRM_AMOUNT_MISMATCH,
    /** X-Internal-Token validation failed on an internal S2S endpoint — the
     *  trust boundary was probed or a caller is misconfigured. Metadata carries
     *  the path, NEVER the token itself. */
    INTERNAL_TOKEN_REJECTED,
    /** The nightly audit-integrity verifier found tampered rows or a broken
     *  chain link — recorded so the incident itself lands in the (still
     *  tamper-evident) trail. */
    AUDIT_VERIFICATION_FAILED
}
