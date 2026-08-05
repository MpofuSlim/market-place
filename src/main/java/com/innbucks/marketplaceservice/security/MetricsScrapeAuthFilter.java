package com.innbucks.marketplaceservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Gates {@code /actuator/prometheus} on a static shared token
 * ({@code X-Metrics-Token} header, constant-time compare), mirroring the
 * fleet's X-Internal-Token pattern for S2S endpoints.
 *
 * <p>Why not a JWT: a scraper cannot renew a customer JWT — a static token
 * file in the Prometheus container would go stale at the first expiry and
 * every metric-based alert would silently go blind. Why not open: metrics
 * (endpoint names, error rates, business volumes) stay behind an explicit
 * credential — the gateway's api-docs proxy tunnel has historically made
 * "in-cluster only" an unsafe assumption for service-local paths.
 *
 * <p>Unlike InnRewards' grant-only variant, this filter REJECTS: here
 * SecurityConfig permitAlls the scrape path, so this filter is the sole gate
 * and a wrong/absent header must stop the request itself. Fail-closed: with
 * {@code monitoring.scrape-token} blank/unset (the default) nothing passes —
 * enabling scraping is an explicit per-cell provisioning step
 * ({@code METRICS_SCRAPE_TOKEN}, lock-step with the fleet Prometheus job).
 * Every other path is untouched ({@link #shouldNotFilter}).
 */
@Component
public class MetricsScrapeAuthFilter extends OncePerRequestFilter {

    private static final String SCRAPE_PATH = "/actuator/prometheus";
    private static final String TOKEN_HEADER = "X-Metrics-Token";

    private final byte[] scrapeToken;

    public MetricsScrapeAuthFilter(@Value("${monitoring.scrape-token:}") String scrapeToken) {
        String trimmed = scrapeToken == null ? "" : scrapeToken.trim();
        this.scrapeToken = trimmed.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SCRAPE_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String presented = request.getHeader(TOKEN_HEADER);
        if (scrapeToken.length > 0
                && presented != null
                && MessageDigest.isEqual(scrapeToken, presented.getBytes(StandardCharsets.UTF_8))) {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        "metrics-scraper", null,
                        List.of(new SimpleGrantedAuthority("ROLE_METRICS_SCRAPE")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            filterChain.doFilter(request, response);
            return;
        }
        // Same 401 envelope SecurityConfig's entry point writes, so the caller
        // sees one shape regardless of which layer rejected it.
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid or missing token\",\"data\":null}");
    }
}
