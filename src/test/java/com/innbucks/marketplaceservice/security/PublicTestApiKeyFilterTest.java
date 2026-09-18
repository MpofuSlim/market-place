package com.innbucks.marketplaceservice.security;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Pins the gate's three states and its boundary. Carried from loyalty-service's
 * {@code PublicTestApiKeyFilterTest} — keep the two in lock-step.
 */
class PublicTestApiKeyFilterTest {

    private static final String KEY = "s3cret-public-test-key";

    private final MarketplaceMetrics metrics = new MarketplaceMetrics(new SimpleMeterRegistry());

    private PublicTestApiKeyFilter filter(boolean enabled, String apiKey) {
        return new PublicTestApiKeyFilter(enabled, apiKey, metrics);
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    void theFilterIsInertWhenTheSurfaceIsOff() {
        // A 401 here would confirm something sits behind a path the controller
        // is about to answer 404 for — the two must not contradict each other.
        assertThat(filter(false, KEY).shouldNotFilter(
                request("GET", "/marketplace/public/buyers/alice/cart"))).isTrue();
    }

    @Test
    void theGateIsOptInSoNoKeyMeansNoGate() {
        assertThat(filter(true, "").shouldNotFilter(
                request("GET", "/marketplace/public/buyers/alice/cart"))).isTrue();
        assertThat(filter(true, "   ").shouldNotFilter(
                request("GET", "/marketplace/public/buyers/alice/cart"))).isTrue();
    }

    @Test
    void theGateCoversThePrefixWhenAKeyIsSet() {
        assertThat(filter(true, KEY).shouldNotFilter(
                request("GET", "/marketplace/public/buyers/alice/cart"))).isFalse();
        assertThat(filter(true, KEY).shouldNotFilter(
                request("GET", "/marketplace/public"))).isFalse();
    }

    @Test
    void theGateStopsAtThePrefixBoundary() {
        // A path that merely STARTS with the characters is a different,
        // authenticated endpoint and must not be dragged under this gate.
        assertThat(filter(true, KEY).shouldNotFilter(
                request("GET", "/marketplace/publications"))).isTrue();
        assertThat(filter(true, KEY).shouldNotFilter(
                request("GET", "/marketplace/catalog"))).isTrue();
    }

    @Test
    void preflightIsNeverGated() {
        // A CORS preflight carries no custom headers by construction, so gating
        // it would 401 the preflight and kill the real call before it is sent.
        assertThat(filter(true, KEY).shouldNotFilter(
                request("OPTIONS", "/marketplace/public/buyers/alice/cart"))).isTrue();
    }

    @Test
    void aMissingKeyIsRefusedWithTheFleetEnvelope() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter(true, KEY).doFilter(
                request("GET", "/marketplace/public/buyers/alice/cart"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid or missing API key");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aWrongKeyIsRefusedIndistinguishablyFromAMissingOne() throws Exception {
        MockHttpServletResponse missing = new MockHttpServletResponse();
        MockHttpServletResponse wrong = new MockHttpServletResponse();

        filter(true, KEY).doFilter(
                request("GET", "/marketplace/public/buyers/alice/cart"), missing, mock(FilterChain.class));

        MockHttpServletRequest withWrongKey = request("GET", "/marketplace/public/buyers/alice/cart");
        withWrongKey.addHeader("x-api-key", "not-the-key");
        filter(true, KEY).doFilter(withWrongKey, wrong, mock(FilterChain.class));

        // Same status AND same body: which of the two it was goes to the metric,
        // never to the caller.
        assertThat(wrong.getStatus()).isEqualTo(missing.getStatus());
        assertThat(wrong.getContentAsString()).isEqualTo(missing.getContentAsString());
    }

    @Test
    void aKeyThatIsAPrefixOfTheRealOneIsRefused() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = request("GET", "/marketplace/public/buyers/alice/cart");
        request.addHeader("x-api-key", KEY.substring(0, KEY.length() - 1));

        filter(true, KEY).doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void theCorrectKeyPassesThrough() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = request("GET", "/marketplace/public/buyers/alice/cart");
        request.addHeader("x-api-key", KEY);

        filter(true, KEY).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(request, response);
    }
}
