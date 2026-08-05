package com.innbucks.marketplaceservice.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared-secret check for the S2S surface (/marketplace/internal/**): the
 * platform payments service presents {@code X-Internal-Token}, compared
 * constant-time against {@code innbucks.internal-api-token}. Controllers call
 * {@link #authorized} and 401 on {@code false} — this is leg one of the fleet's
 * "three files must agree" rule (this repo's SecurityConfig permitAlls the path;
 * the fleet gateway edge-denies it, see docs/fleet-wiring.md).
 *
 * <p>Fail-closed on a blank configured token (nothing authorizes until the cell
 * provisions INTERNAL_API_TOKEN; ProductionSecretsGuard makes it boot-required
 * under deployment profiles). Never throws — a rejection is a return value plus
 * a {@code marketplace.internal.token.rejected} tick, so a probe can't turn
 * into a 500.
 */
@Component
public class InternalTokenAuthorizer {

    private static final String TOKEN_HEADER = "X-Internal-Token";

    private final byte[] expectedToken;
    private final Counter rejected;

    public InternalTokenAuthorizer(@Value("${innbucks.internal-api-token:}") String internalApiToken,
                                   MeterRegistry meterRegistry) {
        String trimmed = internalApiToken == null ? "" : internalApiToken.trim();
        this.expectedToken = trimmed.getBytes(StandardCharsets.UTF_8);
        this.rejected = meterRegistry.counter("marketplace.internal.token.rejected");
    }

    /**
     * @return {@code true} only when a non-blank token is configured AND the
     *         request's {@code X-Internal-Token} matches it (constant-time).
     */
    public boolean authorized(HttpServletRequest request) {
        String presented = request == null ? null : request.getHeader(TOKEN_HEADER);
        boolean ok = expectedToken.length > 0
                && presented != null
                && MessageDigest.isEqual(expectedToken, presented.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            rejected.increment();
        }
        return ok;
    }
}
