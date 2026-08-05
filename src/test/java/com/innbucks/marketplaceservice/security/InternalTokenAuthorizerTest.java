package com.innbucks.marketplaceservice.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link InternalTokenAuthorizer}, leg one of the fleet "three files must
 * agree" rule: constant-time X-Internal-Token comparison
 * ({@code MessageDigest.isEqual} — the same path for match and mismatch),
 * fail-closed on an unprovisioned token, and every rejection ticking
 * {@code marketplace.internal.token.rejected} so trust-boundary probes surface
 * on the dashboard.
 */
class InternalTokenAuthorizerTest {

    private static final String TOKEN = "internal-token-unit-0123456789abcdef";

    private SimpleMeterRegistry registry;
    private InternalTokenAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        authorizer = new InternalTokenAuthorizer(TOKEN, registry);
    }

    private double rejectedCount() {
        return registry.get("marketplace.internal.token.rejected").counter().count();
    }

    private static MockHttpServletRequest requestWithToken(String presented) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/marketplace/internal/orders/MKT-abc123def456");
        if (presented != null) {
            request.addHeader("X-Internal-Token", presented);
        }
        return request;
    }

    @Test
    void correctTokenAuthorizesWithoutTickingTheMetric() {
        assertThat(authorizer.authorized(requestWithToken(TOKEN))).isTrue();
        assertThat(rejectedCount()).isZero();
    }

    @Test
    void wrongTokenIsRejectedAndTheMetricIncremented() {
        assertThat(authorizer.authorized(requestWithToken("wrong-token"))).isFalse();
        assertThat(rejectedCount()).isEqualTo(1.0);

        // Every rejection counts — a probe hammering the boundary is visible.
        assertThat(authorizer.authorized(requestWithToken("still-wrong"))).isFalse();
        assertThat(rejectedCount()).isEqualTo(2.0);
    }

    @Test
    void missingHeaderIsRejected() {
        assertThat(authorizer.authorized(requestWithToken(null))).isFalse();
        assertThat(rejectedCount()).isEqualTo(1.0);
    }

    @Test
    void nullRequestIsRejectedNotThrown() {
        // Never throws — a rejection is a return value, not a 500.
        assertThat(authorizer.authorized(null)).isFalse();
        assertThat(rejectedCount()).isEqualTo(1.0);
    }

    @Test
    void blankConfiguredTokenFailsClosed() {
        // Until the cell provisions INTERNAL_API_TOKEN nothing authorizes —
        // even a blank presented header that would "match" the blank config.
        InternalTokenAuthorizer unprovisioned =
                new InternalTokenAuthorizer("", registry);

        assertThat(unprovisioned.authorized(requestWithToken(""))).isFalse();
        assertThat(unprovisioned.authorized(requestWithToken(TOKEN))).isFalse();
        assertThat(rejectedCount()).isEqualTo(2.0);
    }

    @Test
    void whitespaceOnlyConfiguredTokenAlsoFailsClosed() {
        InternalTokenAuthorizer unprovisioned =
                new InternalTokenAuthorizer("   ", registry);

        assertThat(unprovisioned.authorized(requestWithToken("   "))).isFalse();
    }

    @Test
    void configuredTokenIsTrimmedBeforeComparing() {
        // Env vars picked up with a stray trailing newline still authorize the
        // real token value.
        InternalTokenAuthorizer trimmed =
                new InternalTokenAuthorizer("  " + TOKEN + "\n", registry);

        assertThat(trimmed.authorized(requestWithToken(TOKEN))).isTrue();
    }
}
