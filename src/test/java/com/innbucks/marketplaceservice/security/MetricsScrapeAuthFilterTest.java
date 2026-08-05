package com.innbucks.marketplaceservice.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link MetricsScrapeAuthFilter}: the sole gate on
 * {@code /actuator/prometheus} (SecurityConfig permitAlls the path), so it
 * must REJECT — and fail closed when no scrape token is provisioned. Every
 * other path passes through untouched.
 */
class MetricsScrapeAuthFilterTest {

    private static final String SCRAPE_PATH = "/actuator/prometheus";
    private static final String TOKEN = "scrape-token-unit-0123456789abcdef";

    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest scrapeRequest(String presentedToken) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", SCRAPE_PATH);
        if (presentedToken != null) {
            request.addHeader("X-Metrics-Token", presentedToken);
        }
        return request;
    }

    @Test
    void correctTokenPassesAndGrantsTheScrapeAuthority() throws Exception {
        new MetricsScrapeAuthFilter(TOKEN).doFilter(scrapeRequest(TOKEN), response, chain);

        assertThat(chain.getRequest()).isNotNull();          // chain continued
        assertThat(response.getStatus()).isEqualTo(200);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_METRICS_SCRAPE");
    }

    @Test
    void wrongTokenIsRejectedWithTheEnvelopeBody() throws Exception {
        new MetricsScrapeAuthFilter(TOKEN).doFilter(
                scrapeRequest("wrong-token"), response, chain);

        assertThat(chain.getRequest()).isNull();             // never reached the chain
        assertThat(response.getStatus()).isEqualTo(401);
        // Same ApiResult 401 shape SecurityConfig's entry point writes.
        assertThat(response.getContentAsString())
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("Invalid or missing token");
    }

    @Test
    void missingHeaderIsRejected() throws Exception {
        new MetricsScrapeAuthFilter(TOKEN).doFilter(scrapeRequest(null), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void blankConfiguredTokenFailsClosed() throws Exception {
        // Unprovisioned METRICS_SCRAPE_TOKEN (the default "") must let NOTHING
        // through — not even an empty presented header that would trivially
        // "match" the empty expectation.
        new MetricsScrapeAuthFilter("").doFilter(scrapeRequest(""), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void whitespaceOnlyConfiguredTokenAlsoFailsClosed() throws Exception {
        new MetricsScrapeAuthFilter("   ").doFilter(scrapeRequest("   "), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void otherPathsPassThroughUntouched() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/marketplace/catalog");

        new MetricsScrapeAuthFilter(TOKEN).doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
