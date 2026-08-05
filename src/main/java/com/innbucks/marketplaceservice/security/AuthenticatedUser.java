package com.innbucks.marketplaceservice.security;

import java.util.Set;

/**
 * The authenticated caller, extracted from the verified fleet JWT by
 * {@link JwtFilter} and set as the {@code Authentication} principal. Retrieve
 * it in controllers/services via {@link CurrentUser}.
 *
 * <p>{@code uuid} is the caller's stable cross-service user uuid — the
 * {@code userUuid} claim on fleet tokens, falling back to the subject for
 * legacy/test tokens whose subject IS the uuid. {@code merchantId}/{@code
 * shopId} are the seller-scoping claims minted by user-service (listing
 * administration is MERCHANT_ADMIN-only by owner decision); merchant scope
 * comes from here, NEVER from a request body. {@code phone}/{@code country} are set for
 * CUSTOMER tokens that carry them. All fields except {@code uuid} and
 * {@code roles} may be null (legacy tokens, staff tokens).
 */
public record AuthenticatedUser(
        String uuid,
        Set<String> roles,
        String merchantId,
        String shopId,
        String phone,
        String country) {

    public AuthenticatedUser {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
