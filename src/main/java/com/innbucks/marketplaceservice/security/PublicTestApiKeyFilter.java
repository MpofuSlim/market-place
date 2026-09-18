package com.innbucks.marketplaceservice.security;

import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Gates the {@code /marketplace/public/**} test surface behind a shared
 * {@code x-api-key} header, compared in constant time.
 *
 * <p>A faithful carry of loyalty-service's {@code PublicTestApiKeyFilter} —
 * same shape, same opt-in semantics, same reasoning. Keep the two in lock-step;
 * an app that holds one key for a cell should not meet two different gate
 * behaviours across two services.
 *
 * <h2>Why a filter and not a check in the controller</h2>
 * {@code SecurityConfig} declares {@code /marketplace/public/**}
 * {@code permitAll()}, so nothing in the Spring Security chain asks who is
 * calling. A per-method check would work, but it has to be remembered on every
 * endpoint added later — and the one that forgets is a live, unauthenticated
 * mapping. A filter covers the prefix by shape, so a new mapping under it is
 * gated the moment it exists.
 *
 * <h2>This authenticates the APP, not the customer</h2>
 * The key ships in the client, so anyone who can read the app's config can read
 * the key. It is a <b>throttle and a kill switch</b> — it stops casual traffic
 * and drive-by scanners, and it revokes the whole surface without an app
 * release. What keeps this surface off production is
 * {@code marketplace.public-test.enabled} staying false.
 *
 * <h2>The gate is OPT-IN: no key configured means no gate</h2>
 * <ul>
 *   <li><b>Surface off</b> — inert. The controller answers 404; a 401 here
 *       would contradict that by confirming something sits behind the path.</li>
 *   <li><b>Surface on, no key</b> — inert, and the endpoints answer as they
 *       would with this class absent.</li>
 *   <li><b>Surface on, key set</b> — one opaque <b>401</b> for a missing key
 *       and a wrong key alike. The metric tag separates them for us; the caller
 *       learns nothing either way.</li>
 * </ul>
 *
 * <p>{@code OPTIONS} is never gated: a CORS preflight carries no custom headers
 * by construction, so gating it would 401 the preflight and break the browser
 * call before the real request is ever sent.
 */
@Component
public class PublicTestApiKeyFilter extends OncePerRequestFilter {

    static final String PATH_PREFIX = "/marketplace/public";
    static final String API_KEY_HEADER = "x-api-key";

    private final boolean enabled;
    private final byte[] apiKey;
    private final MarketplaceMetrics metrics;

    public PublicTestApiKeyFilter(@Value("${marketplace.public-test.enabled:false}") boolean enabled,
                                  @Value("${marketplace.public-test.api-key:}") String apiKey,
                                  MarketplaceMetrics metrics) {
        this.enabled = enabled;
        String trimmed = apiKey == null ? "" : apiKey.trim();
        this.apiKey = trimmed.getBytes(StandardCharsets.UTF_8);
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        if (apiKey.length == 0) {
            // Opt-in: no key provisioned, no gate. Decided HERE rather than in
            // doFilterInternal so the request never enters the filter body at
            // all — there is no refusal to accidentally reach, and no per-call
            // counter to drown the metric in on a cell that simply has no key.
            return true;
        }
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        return !covers(request.getRequestURI());
    }

    /**
     * Exactly the prefix, not anything that merely starts with its characters —
     * a future {@code /marketplace/publications} would be a different
     * (authenticated) path and must not be dragged under this gate, in either
     * direction.
     */
    private static boolean covers(String uri) {
        return uri != null && (uri.equals(PATH_PREFIX) || uri.startsWith(PATH_PREFIX + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String presented = request.getHeader(API_KEY_HEADER);
        if (presented == null || presented.isBlank()) {
            metrics.publicTestRejected("missing_key");
            refuse(response);
            return;
        }
        // Constant-time compare — String.equals exits at the first differing
        // byte and leaks the key one byte at a time to a patient caller.
        if (!MessageDigest.isEqual(apiKey, presented.getBytes(StandardCharsets.UTF_8))) {
            metrics.publicTestRejected("bad_key");
            refuse(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** The {@code ApiResult} envelope every other refusal in this service uses. */
    private void refuse(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(
                "{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid or missing API key\",\"data\":null}");
    }
}
