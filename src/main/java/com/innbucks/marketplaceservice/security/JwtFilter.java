package com.innbucks.marketplaceservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Verifies the Bearer JWT and, on success, sets the request's
 * {@code Authentication} with principal {@link AuthenticatedUser} and
 * authorities {@code ROLE_<role>}.
 *
 * <p>Deliberately NON-rejecting (unlike InnRewards' filter, which writes the
 * 401 itself): an absent, invalid, revoked or superseded token simply leaves
 * the request unauthenticated and the chain continues — {@link SecurityConfig}
 * decides whether the path required auth, and its entry point renders the one
 * fleet-envelope 401. That keeps public paths (catalog GETs) usable with a
 * stale token instead of hard-failing them.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtFilter extends OncePerRequestFilter {

    /** MDC key for the customer's home-country routing tag, sourced from the
     *  {@code homeCountry} JWT claim (distinct from the deployment-country pin
     *  logged as {@code country}). Picked up by the logstash encoder. */
    public static final String HOME_COUNTRY_MDC_KEY = "homeCountry";

    private final JwtUtil jwtUtil;

    /** Shared cross-service logout denylist (Redis). Checked after the token
     *  passes signature/claim validation, so a logged-out token is rejected
     *  fleet-wide instead of lingering until its short TTL expires. */
    private final RevokedTokenDenylist revokedTokenDenylist;

    /** Shared cross-service session-supersession store (Redis). Checked right
     *  after the denylist so a token superseded by a newer login / password
     *  change is rejected immediately instead of living out its TTL. */
    private final TokenVersionStore tokenVersionStore;

    static final String MERCHANT_ADMIN = "MERCHANT_ADMIN";
    static final String COURIER = "COURIER";
    static final String MARKETPLACE_PRODUCT = "marketplace";
    private static final Set<String> RUNS_ORGANIZATION = Set.of("OWNER", "ADMIN");
    private static final Set<String> MEMBER_OF_ORGANIZATION = Set.of("OWNER", "ADMIN", "STAFF");

    private static final List<String> EXCLUDED_PATHS = List.of(
            "/swagger-ui",
            "/v3/api-docs",
            "/error",
            "/actuator",
            "/marketplace/internal"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        // No Authorization header — let the chain proceed unauthenticated.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        String homeCountry = null;
        try {
            if (jwtUtil.isTokenValid(token) && !isRejected(token, request)) {
                String organizationId = jwtUtil.extractOrganizationId(token);
                String organizationRole = jwtUtil.extractOrganizationRole(token);
                Set<String> products = jwtUtil.extractProducts(token);
                String seller = sellingOrganizationOf(organizationId, organizationRole, products);
                String courier = deliveringOrganizationOf(organizationId, organizationRole, products);
                AuthenticatedUser user = new AuthenticatedUser(
                        jwtUtil.extractUserUuid(token),
                        courierRoles(sellerRoles(jwtUtil.extractRoles(token), seller), courier),
                        seller,
                        null,
                        jwtUtil.extractPhoneNumber(token),
                        jwtUtil.extractCountry(token),
                        courier);

                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                for (String role : user.roles()) {
                    if (role != null && !role.isBlank()) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                    }
                }

                var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
                homeCountry = user.country();
                // TRACE, not DEBUG: this line carries identity + authz on every
                // authenticated request. Keep it off by default everywhere.
                log.trace("JWT authenticated uuid={} roles={} sellerOrganization={} path={}",
                        user.uuid(), user.roles(), user.merchantId(), request.getRequestURI());
            }
        } catch (Exception e) {
            // Defensive: if claim extraction blows up for any reason (corrupt
            // payload, etc.) treat it the same as an invalid token — the request
            // continues unauthenticated, never half-authenticated. Never log the
            // token itself.
            SecurityContextHolder.clearContext();
            log.warn("JWT validation error path={} message={}", request.getRequestURI(), e.getMessage());
        }

        // Push the customer's homeCountry into MDC for the downstream chain;
        // cleared in finally so request-thread recycling doesn't leak it into
        // the next request.
        boolean mdcSet = false;
        if (homeCountry != null && !homeCountry.isBlank()) {
            MDC.put(HOME_COUNTRY_MDC_KEY, homeCountry);
            mdcSet = true;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (mdcSet) {
                MDC.remove(HOME_COUNTRY_MDC_KEY);
            }
        }
    }

    /**
     * The organization a caller SELLS for, or null. Seller authority here is
     * decided by the organization claims (user-service V39), never by the role
     * alone: the session's {@code orgId}, when the caller is its OWNER or ADMIN
     * and it holds the {@code marketplace} product. Every part is required —
     * STAFF does not run the business, a loyalty-only business does not sell
     * here, and a session that has not chosen among several organizations has
     * nothing to sell for. Product and role names match exactly, as
     * user-service mints them.
     */
    static String sellingOrganizationOf(String organizationId, String organizationRole, Set<String> products) {
        if (organizationId == null || organizationRole == null || products == null) {
            return null;
        }
        if (!RUNS_ORGANIZATION.contains(organizationRole) || !products.contains(MARKETPLACE_PRODUCT)) {
            return null;
        }
        return organizationId;
    }

    /**
     * The organization whose parcels a caller may CARRY (V14), or null: any
     * member — OWNER, ADMIN or STAFF — of an organization holding the
     * {@code marketplace} product. Wider than {@link #sellingOrganizationOf} on
     * purpose, because delivery drivers are usually STAFF; and it buys exactly
     * one thing, the courier surface — posting positions for that
     * organization's parcels in transit, and seeing which those are.
     */
    static String deliveringOrganizationOf(String organizationId, String organizationRole,
                                           Set<String> products) {
        if (organizationId == null || organizationRole == null || products == null) {
            return null;
        }
        if (!MEMBER_OF_ORGANIZATION.contains(organizationRole)
                || !products.contains(MARKETPLACE_PRODUCT)) {
            return null;
        }
        return organizationId;
    }

    /** {@code COURIER} decided by {@link #deliveringOrganizationOf} alone —
     *  user-service never mints it, so a token claiming it is not believed. */
    static Set<String> courierRoles(Set<String> roles, String deliveringOrganization) {
        Set<String> out = new LinkedHashSet<>(roles);
        out.remove(COURIER);
        if (deliveringOrganization != null) {
            out.add(COURIER);
        }
        return out;
    }

    /**
     * The token's roles with {@code MERCHANT_ADMIN} decided by
     * {@link #sellingOrganizationOf} alone: dropped when the token carries it
     * without a selling organization (a loyalty-only merchant admin, a
     * pre-organizations token), added when it does not but the caller runs a
     * selling organization (an ADMIN colleague with no staff role at all).
     */
    static Set<String> sellerRoles(Set<String> tokenRoles, String sellingOrganization) {
        Set<String> roles = new LinkedHashSet<>(tokenRoles == null ? Set.of() : tokenRoles);
        roles.remove(MERCHANT_ADMIN);
        if (sellingOrganization != null) {
            roles.add(MERCHANT_ADMIN);
        }
        return roles;
    }

    /**
     * Post-signature rejection gates, in InnRewards' order: shared logout
     * denylist, session supersession (tokenVersion), mustChangePassword.
     * Each gate fails open on missing data / Redis trouble — see the stores.
     */
    private boolean isRejected(String token, HttpServletRequest request) {
        if (revokedTokenDenylist.isRevoked(token)) {
            log.warn("Rejected revoked (logged-out) token path={}", request.getRequestURI());
            return true;
        }

        // Cross-service session supersession (OWASP A07 / CWE-613). Reject a
        // token whose tokenVersion claim is strictly below the fleet-current
        // value user-service published for this user — otherwise it keeps
        // working here until its short TTL elapses. Fail-open: a legacy token
        // with no uuid / tokenVersion claim, or a Redis blip (currentVersion
        // == null), passes through so a store outage never 401s everyone.
        UUID userUuid = jwtUtil.extractUserId(token);
        if (userUuid != null) {
            Long currentVersion = tokenVersionStore.currentVersion(userUuid.toString());
            if (currentVersion != null) {
                Long tokenVersion = jwtUtil.extractTokenVersion(token);
                if (tokenVersion != null && tokenVersion < currentVersion) {
                    log.warn("Rejected superseded token (tokenVersion={} < current={}) path={}",
                            tokenVersion, currentVersion, request.getRequestURI());
                    return true;
                }
            }
        }

        // mustChangePassword gate: user-service mints tokens with this claim
        // for accounts that haven't rotated their temp password yet; every
        // other fleet service refuses those tokens until the rotation happens.
        if (jwtUtil.extractMustChangePassword(token)) {
            log.warn("Rejected token pending password change path={}", request.getRequestURI());
            return true;
        }
        return false;
    }
}
