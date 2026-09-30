package com.innbucks.marketplaceservice.security;

import java.util.Set;

/**
 * The authenticated caller, extracted from the verified fleet JWT by
 * {@link JwtFilter} and set as the {@code Authentication} principal. Retrieve
 * it in controllers/services via {@link CurrentUser}.
 *
 * <p>{@code uuid} is the caller's stable cross-service user uuid — the
 * {@code userUuid} claim on fleet tokens, falling back to the subject for
 * legacy/test tokens whose subject IS the uuid.
 *
 * <p>{@code merchantId} is the SELLER scope: the id of the ORGANIZATION the
 * session sells for (the token's {@code orgId}, when the caller is its OWNER
 * or ADMIN and it holds the {@code marketplace} product — see
 * {@link JwtFilter#sellingOrganizationOf}). The name is kept because every
 * seller column and API field in this service is called {@code merchantId};
 * the value stopped being a loyalty merchant id when user-service stopped
 * minting that claim. Seller scope comes from here, NEVER from a request body.
 * {@code shopId} is always null on a verified token — a loyalty shop id means
 * nothing beside an organization — and survives only for the pre-existing
 * listing column. {@code phone}/{@code country} are set for CUSTOMER tokens
 * that carry them. All fields except {@code uuid} and {@code roles} may be
 * null (legacy tokens, staff tokens).
 *
 * <p>{@code deliversFor} (V14) is the organization whose parcels this caller
 * may carry and report positions for — any member of a selling organization,
 * STAFF included, where {@code merchantId} is OWNER/ADMIN only. It grants the
 * courier surface ({@code /marketplace/deliveries}) and nothing else: every
 * seller endpoint still checks {@code MERCHANT_ADMIN} and {@code merchantId}.
 *
 * <p>{@code login} is the token's subject (a staff account's e-mail) and
 * {@code permissions} its {@code perms} claim (user-service V35), already
 * filtered to permission-shaped codes by {@link JwtUtil#extractPermissions}.
 * They exist for the customer-support surface ({@code /marketplace/support/**}),
 * which is gated on permissions, never on roles — see {@code SupportPermissions}.
 */
public record AuthenticatedUser(
        String uuid,
        Set<String> roles,
        String merchantId,
        String shopId,
        String phone,
        String country,
        String deliversFor,
        String login,
        Set<String> permissions) {

    public AuthenticatedUser {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /** A caller built without the support fields: no login, no permissions. */
    public AuthenticatedUser(String uuid, Set<String> roles, String merchantId, String shopId,
                             String phone, String country, String deliversFor) {
        this(uuid, roles, merchantId, shopId, phone, country, deliversFor, null, Set.of());
    }

    /** A caller with no courier scope beyond their seller scope: someone who
     *  runs the business may carry its parcels too. */
    public AuthenticatedUser(String uuid, Set<String> roles, String merchantId, String shopId,
                             String phone, String country) {
        this(uuid, roles, merchantId, shopId, phone, country, merchantId);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    /** Fleet oversight role: may administer ANY merchant's listings (including
     *  on-behalf creation) and read all listings/orders — but never carries a
     *  merchant scope of its own and cannot place or cancel orders. */
    public boolean isSuperAdmin() {
        return roles.contains("SUPER_ADMIN");
    }
}
