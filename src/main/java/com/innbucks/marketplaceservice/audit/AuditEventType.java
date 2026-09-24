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
    /** A seller trust record was created on a merchant's first listing (V8). */
    SELLER_REGISTERED,
    /** A SUPER_ADMIN approved / rejected / suspended / reinstated a seller (V8).
     *  Metadata carries from, to, the note, and how many live listings a
     *  suspension took down. */
    SELLER_STATUS_CHANGED,

    /**
     * A seller's payout destination was set or changed (V13). Audited because
     * redirecting a payout is THE attack on that feature: the tamper-evident
     * chain is what makes "who moved it, and when" answerable after the money
     * has gone. The destination's own values are NOT in the metadata — the
     * method and whether one existed before is enough to investigate with,
     * and an account number in the audit log is an account number in one more
     * place (V7's free-text stance, applied to money).
     */
    SELLER_PAYOUT_DESTINATION_CHANGED,
    /** A seller (or an operator for them) added, changed, removed or re-defaulted
     *  a collection point (V18). Metadata carries the point id, its town and
     *  who acted — never the address or phone (V7's free-text stance). */
    COLLECTION_POINT_CREATED,
    COLLECTION_POINT_UPDATED,
    COLLECTION_POINT_DELETED,
    COLLECTION_POINT_DEFAULT_CHANGED,
    /** A merchant created a listing. */
    LISTING_CREATED,
    /** A merchant updated a listing's content (title/description/price/stock). */
    LISTING_UPDATED,
    /** A listing moved between DRAFT/ACTIVE/INACTIVE/ARCHIVED. */
    LISTING_STATUS_CHANGED,
    /** A listing's PRIMARY image was uploaded or replaced in place. */
    LISTING_IMAGE_UPDATED,
    /** A non-primary image was appended to a listing's gallery (or the sole
     *  image of a previously-empty gallery, which becomes primary). */
    LISTING_IMAGE_ADDED,
    /** A gallery image row was removed (metadata records whether it was the
     *  primary — a survivor is auto-promoted in that case). */
    LISTING_IMAGE_DELETED,
    /** A different gallery image was promoted to primary (atomic swap). */
    LISTING_IMAGE_PRIMARY_CHANGED,
    /** A verified buyer created a review (eligibility: a PAID order containing
     *  the listing; metadata records the qualifying orderId). */
    REVIEW_CREATED,
    /** A buyer edited their own review (metadata records the rating delta the
     *  aggregates absorbed). */
    REVIEW_UPDATED,
    /** A review was removed — by its author or by SUPER_ADMIN moderation
     *  (metadata records {@code adminRemoval}). */
    REVIEW_DELETED,
    /** An authenticated user reported a listing to the moderation queue
     *  (metadata carries the reason, never free-text detail). */
    LISTING_REPORTED,
    /** SUPER_ADMIN closed a report — resolved or dismissed (metadata records
     *  the action and whether the listing was deactivated with it). */
    LISTING_REPORT_RESOLVED,
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
    /** A seller dispatched their parcel of a paid order (V9). Metadata carries
     *  the order, the merchant and the state it moved from. */
    FULFILMENT_DISPATCHED,
    /** A parcel was closed as delivered (V9) — metadata's {@code deliveredBy}
     *  records whether the BUYER confirmed receipt or the seller marked it, a
     *  distinction any delivery dispute turns on. */
    FULFILMENT_DELIVERED,
    /** A buyer disputed a parcel — its settlement froze until an operator
     *  decides (V10). Metadata carries the bounded reason, NEVER the buyer's
     *  free-text detail. */
    /** A parcel's collection-code budget ran out — ten wrong codes against
     *  one parcel is not a typo, and only the seller holding it can submit
     *  one. Recorded once: the lock short-circuits every later attempt, so
     *  this can never flood the chain. */
    COLLECT_CODE_LOCKED,
    /** An operator recorded a refund they sent for a parcel the seller could
     *  not supply (V12). The dispute path has its own event; this is the one
     *  nobody argued about. */
    SETTLEMENT_REFUNDED,
    /** A seller declared a parcel unfulfillable — the decision that turns a
     *  buyer's money around. */
    FULFILMENT_UNFULFILLED,
    SETTLEMENT_DISPUTED,
    /** An operator resolved a dispute (V10) — metadata's {@code action} says
     *  which way (RELEASE to the seller / REFUND to the buyer), with the
     *  money and the refund reference where one was recorded. */
    SETTLEMENT_DISPUTE_RESOLVED,
    /** An operator marked one merchant's RELEASABLE settlements paid (V10) —
     *  ONE event per payout run (one operator decision), carrying the
     *  merchant, parcel count, total net and the payout reference; the
     *  per-parcel trail lives in each order's journal. */
    SETTLEMENT_PAID_OUT,
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
