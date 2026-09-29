package com.innbucks.marketplaceservice.security;

import com.innbucks.marketplaceservice.support.TestJwts;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins {@link JwtFilter}'s post-signature rejection gates — the shared logout
 * denylist, tokenVersion supersession and the mustChangePassword claim — by
 * driving the REAL filter ({@code doFilter}, so {@code shouldNotFilter} runs
 * too) over a real {@link JwtUtil} and MOCKED stores. Every IT runs with Redis
 * unreachable, i.e. only the stores' fail-open branches; this is where the
 * comparisons themselves are held.
 *
 * <p>The filter is deliberately NON-rejecting: a refused token leaves the
 * request unauthenticated, the chain continues, and {@code SecurityConfig}'s
 * entry point renders the 401 envelope for a protected path (proven over HTTP
 * against a real Redis by {@code RedisTokenRevocationIT}). So every case here
 * asserts two things: whether an {@code Authentication} existed when the chain
 * ran, and that the filter itself wrote nothing.
 */
class JwtFilterRevocationTest {

    private static final String SECRET = "unit-test-jwt-secret-7c1d9e4f2a6b8c0d3e5f7a9b";
    private static final UUID USER = UUID.fromString("0b9f4a6e-1c2d-4e5f-8a7b-9c0d1e2f3a4b");
    private static final String PROTECTED_PATH = "/marketplace/orders";

    private RevokedTokenDenylist denylist;
    private TokenVersionStore versions;
    private JwtFilter filter;

    @BeforeEach
    void setUp() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "publicKeyPem", "");
        // Mocks, never `new`: the stores' own Redis behaviour is theirs to pin.
        denylist = mock(RevokedTokenDenylist.class);
        versions = mock(TokenVersionStore.class);
        filter = new JwtFilter(jwtUtil, denylist, versions);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** What the rest of the chain saw, and what the filter wrote. */
    private record Outcome(Authentication auth, int chainCalls, MockHttpServletResponse response) {
        boolean authenticated() {
            return auth != null;
        }
    }

    private Outcome run(String path, String authorizationHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        if (authorizationHeader != null) {
            request.addHeader("Authorization", authorizationHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> seen = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (req, res) -> {
            calls.incrementAndGet();
            seen.set(SecurityContextHolder.getContext().getAuthentication());
        };
        filter.doFilter(request, response, chain);
        return new Outcome(seen.get(), calls.get(), response);
    }

    private Outcome bearer(String token) throws Exception {
        return run(PROTECTED_PATH, "Bearer " + token);
    }

    private static TestJwts.Builder customer() {
        return TestJwts.forUser(USER).role("CUSTOMER");
    }

    /** The filter never answers for itself: no status, no body. */
    private static void assertFilterWroteNothing(Outcome outcome) throws Exception {
        assertThat(outcome.chainCalls()).isEqualTo(1);
        assertThat(outcome.response().getStatus()).isEqualTo(200);
        assertThat(outcome.response().getContentAsString()).isEmpty();
    }

    private static void assertRefused(Outcome outcome) throws Exception {
        assertThat(outcome.authenticated()).as("refused token must leave the request unauthenticated")
                .isFalse();
        assertFilterWroteNothing(outcome);
    }

    private static void assertServedAsCustomer(Outcome outcome, UUID expectedUuid) throws Exception {
        assertThat(outcome.authenticated()).isTrue();
        AuthenticatedUser principal = (AuthenticatedUser) outcome.auth().getPrincipal();
        assertThat(principal.uuid()).isEqualTo(expectedUuid.toString());
        assertThat(principal.roles()).containsExactly("CUSTOMER");
        assertThat(outcome.auth().getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CUSTOMER");
        assertFilterWroteNothing(outcome);
    }

    // ------------------------------------------------------------------
    // No / unusable token: the stores are never consulted
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no Authorization header: chain continues unauthenticated, stores untouched")
    void noHeader_continuesUnauthenticated() throws Exception {
        assertRefused(run(PROTECTED_PATH, null));
        verifyNoInteractions(denylist, versions);
    }

    @Test
    @DisplayName("a non-Bearer scheme is ignored, not parsed")
    void nonBearerScheme_isIgnored() throws Exception {
        assertRefused(run(PROTECTED_PATH, "Basic dXNlcjpwYXNz"));
        verifyNoInteractions(denylist, versions);
    }

    @Test
    @DisplayName("a garbage or expired token fails signature/claims before any store lookup")
    void garbageOrExpiredToken_neverReachesTheStores() throws Exception {
        assertRefused(bearer("not.a.jwt"));
        assertRefused(bearer(""));
        assertRefused(bearer(customer().expired().sign(SECRET)));
        assertRefused(bearer(customer().sign("another-secret-entirely-0123456789abcdef")));
        verifyNoInteractions(denylist, versions);
    }

    @Test
    @DisplayName("the internal S2S surface is not filtered at all, so a revoked token cannot matter there")
    void internalSurface_isNotFiltered() throws Exception {
        Outcome outcome = run("/marketplace/internal/orders/MKT-000000000000",
                "Bearer " + customer().sign(SECRET));
        assertRefused(outcome);
        verifyNoInteractions(denylist, versions);
    }

    // ------------------------------------------------------------------
    // Shared logout denylist
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a denylisted (logged-out) token is refused, and supersession is not even asked")
    void denylistedToken_isRefused() throws Exception {
        String token = customer().tokenVersion(3).sign(SECRET);
        when(denylist.isRevoked(token)).thenReturn(true);

        assertRefused(bearer(token));
        verify(denylist).isRevoked(token); // the exact presented string, not a re-encoding
        verifyNoInteractions(versions);
    }

    @Test
    @DisplayName("a token the denylist does not name passes that gate")
    void notDenylisted_passes() throws Exception {
        String token = customer().sign(SECRET);
        when(denylist.isRevoked(anyString())).thenReturn(false);

        assertServedAsCustomer(bearer(token), USER);
        verify(denylist).isRevoked(token);
    }

    // ------------------------------------------------------------------
    // tokenVersion supersession
    // ------------------------------------------------------------------

    @Test
    @DisplayName("tokenVersion strictly BELOW the published current version is refused")
    void supersededTokenVersion_isRefused() throws Exception {
        when(versions.currentVersion(USER.toString())).thenReturn(5L);

        assertRefused(bearer(customer().tokenVersion(4).sign(SECRET)));
        assertRefused(bearer(customer().tokenVersion(0).sign(SECRET)));
    }

    @Test
    @DisplayName("tokenVersion EQUAL to the current version is served (the comparison is strict)")
    void currentTokenVersion_isServed() throws Exception {
        when(versions.currentVersion(USER.toString())).thenReturn(5L);

        assertServedAsCustomer(bearer(customer().tokenVersion(5).sign(SECRET)), USER);
    }

    @Test
    @DisplayName("tokenVersion ABOVE the current version is served (a newer login racing the publish)")
    void newerTokenVersion_isServed() throws Exception {
        when(versions.currentVersion(USER.toString())).thenReturn(5L);

        assertServedAsCustomer(bearer(customer().tokenVersion(6).sign(SECRET)), USER);
    }

    @Test
    @DisplayName("no published version (the store's fail-open answer, e.g. Redis down) is served")
    void noPublishedVersion_failsOpen() throws Exception {
        when(versions.currentVersion(USER.toString())).thenReturn(null);

        assertServedAsCustomer(bearer(customer().tokenVersion(1).sign(SECRET)), USER);
    }

    @Test
    @DisplayName("a legacy token with no tokenVersion claim carries nothing to enforce and is served")
    void legacyTokenWithoutVersion_isServed() throws Exception {
        when(versions.currentVersion(USER.toString())).thenReturn(5L);

        assertServedAsCustomer(bearer(customer().sign(SECRET)), USER);
    }

    @Test
    @DisplayName("the version is looked up under the userUuid CLAIM, not the login-identifier subject")
    void fleetShapedToken_isKeyedOnTheUserUuidClaim() throws Exception {
        when(versions.currentVersion(USER.toString())).thenReturn(5L);

        assertRefused(bearer(customer().loginIdentifier("rudo@example.com").tokenVersion(4).sign(SECRET)));
        verify(versions).currentVersion(USER.toString());
        verify(versions, never()).currentVersion("rudo@example.com");
    }

    @Test
    @DisplayName("a token with no uuid anywhere has no version key, so the store is never asked")
    void tokenWithoutAnyUuid_skipsTheVersionGate() throws Exception {
        // Only reachable with a hand-built token; TestJwts always carries a uuid.
        String token = io.jsonwebtoken.Jwts.builder()
                .subject("rudo@example.com")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .claim("roles", java.util.List.of("CUSTOMER"))
                .claim("tokenVersion", 1)
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        Outcome outcome = bearer(token);
        assertThat(outcome.authenticated()).isTrue();
        verifyNoInteractions(versions);
    }

    // ------------------------------------------------------------------
    // mustChangePassword
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a token pending a password change is refused even when every store passes it")
    void mustChangePassword_isRefused() throws Exception {
        when(versions.currentVersion(USER.toString())).thenReturn(5L);

        assertRefused(bearer(customer().tokenVersion(5).mustChangePassword().sign(SECRET)));
        // Checked last: both store gates were consulted first.
        verify(denylist).isRevoked(anyString());
        verify(versions).currentVersion(USER.toString());
    }

    // ------------------------------------------------------------------
    // A store that THROWS (breaking its own never-throw contract)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a denylist that throws leaves the request unauthenticated — never half-authenticated, never a 500")
    void throwingDenylist_isTreatedAsAnInvalidToken() throws Exception {
        // The stores catch every Redis error themselves and answer "not
        // revoked" / "no version" — that is the fail-open, pinned above. An
        // exception that nevertheless escapes one lands in the filter's
        // defensive catch, which treats it like an invalid token.
        when(denylist.isRevoked(anyString())).thenThrow(new IllegalStateException("boom"));

        assertRefused(bearer(customer().sign(SECRET)));
    }

    @Test
    @DisplayName("a version store that throws is handled the same way")
    void throwingVersionStore_isTreatedAsAnInvalidToken() throws Exception {
        when(versions.currentVersion(anyString())).thenThrow(new IllegalStateException("boom"));

        assertRefused(bearer(customer().tokenVersion(1).sign(SECRET)));
    }
}
