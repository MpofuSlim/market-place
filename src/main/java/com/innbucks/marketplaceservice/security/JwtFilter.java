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
import java.util.List;
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
                AuthenticatedUser user = new AuthenticatedUser(
                        jwtUtil.extractUserUuid(token),
                        jwtUtil.extractRoles(token),
                        jwtUtil.extractMerchantId(token),
                        jwtUtil.extractShopId(token),
                        jwtUtil.extractPhoneNumber(token),
                        jwtUtil.extractCountry(token));

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
                log.trace("JWT authenticated uuid={} roles={} merchantId={} shopId={} path={}",
                        user.uuid(), user.roles(), user.merchantId(), user.shopId(),
                        request.getRequestURI());
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
