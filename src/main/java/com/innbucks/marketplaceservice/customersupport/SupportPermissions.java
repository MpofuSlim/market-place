package com.innbucks.marketplaceservice.customersupport;

/**
 * The permission codes the customer-support surface is gated on. They are
 * defined in user-service's {@code PermissionCatalog} (V45) and granted there to
 * the call-center built-ins — CALL_CENTER_AGENT holds read + manage + messages,
 * CALL_CENTER_SUPERVISOR also supervise, SUPER_ADMIN all of them through its
 * wildcard — and arrive here in the token's {@code perms} claim, which
 * {@code JwtFilter} grants as bare authorities.
 *
 * <p><b>Support endpoints check permissions, never roles.</b> A permission is
 * a capability an operator can grant to any role; naming {@code SUPER_ADMIN} or
 * {@code CALL_CENTER_AGENT} in a {@code hasRole} here would freeze today's
 * role design into code, and a role holding the capability would silently get
 * nothing. The {@code CAN_*} strings are the {@code @PreAuthorize} expressions.
 */
public final class SupportPermissions {

    /** Look up buyers, orders, parcels and sellers, with notes and messages. */
    public static final String READ = "marketplace-support:read";

    /** Routine actions: notes, resends, a fresh collection code, cancel an
     *  unpaid order, open a dispute for a buyer. */
    public static final String MANAGE = "marketplace-support:manage";

    /** Cancel a paid, undispatched parcel for a buyer (refund queued), and
     *  review every agent's support activity. */
    public static final String SUPERVISE = "marketplace-support:supervise";

    /** Type and send an SMS or WhatsApp to a customer on record. Shared with
     *  loyalty-service: the same power whichever product the customer used. */
    public static final String MESSAGES_SEND = "customer-messages:send";

    public static final String CAN_READ = "hasAuthority('" + READ + "')";
    public static final String CAN_MANAGE = "hasAuthority('" + MANAGE + "')";
    public static final String CAN_SUPERVISE = "hasAuthority('" + SUPERVISE + "')";
    public static final String CAN_MESSAGE = "hasAuthority('" + MESSAGES_SEND + "')";

    private SupportPermissions() {
    }
}
