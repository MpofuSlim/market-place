package com.innbucks.marketplaceservice.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Cross-service logout denylist, read side. user-service writes a SHA-256 hash
 * of an explicitly logged-out token to the SHARED Redis under
 * {@code auth:revoked:<sha256HexLower(token)> -> "1"} (see its
 * {@code TokenRevocationService}); this lets {@link JwtFilter} reject such a
 * token the moment the user logs out, rather than waiting out the short
 * access-token TTL. Mirrors the same component in InnRewards / seat-service /
 * payment-service / booking-service.
 *
 * <p>The key scheme MUST match the writer byte-for-byte:
 * {@code "auth:revoked:" + sha256HexLower(token)} where the hash is SHA-256 over
 * {@code token.getBytes(UTF_8)}, hex-encoded lowercase.
 *
 * <p>The {@link StringRedisTemplate} is injected via {@link ObjectProvider} so
 * this bean constructs even when Redis auto-configuration is absent (the test
 * profiles exclude it). With no template — or on any Redis error — the check
 * <b>fails open</b> ({@code false}, "not revoked"): a Redis blip must not 401
 * every authenticated request, and the short access-token TTL is the backstop.
 */
@Component
@Slf4j
public class RevokedTokenDenylist {

    /** Must match user-service's {@code TokenRevocationService.SHARED_DENYLIST_PREFIX}. */
    private static final String SHARED_DENYLIST_PREFIX = "auth:revoked:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public RevokedTokenDenylist(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /**
     * @return {@code true} only when the token's hash is present in the shared
     *         denylist; {@code false} on absence, on a missing Redis template,
     *         AND on any Redis error (fail-open).
     */
    public boolean isRevoked(String token) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            // Redis auto-config disabled (e.g. the test profiles) — fail open.
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(SHARED_DENYLIST_PREFIX + sha256HexLower(token)));
        } catch (RuntimeException ex) {
            // Fail open — the access-token TTL is the backstop.
            log.warn("Shared revoked-token denylist unreachable; allowing token through", ex);
            return false;
        }
    }

    /** SHA-256 of {@code token} (UTF-8 bytes), hex-encoded lowercase. */
    private static String sha256HexLower(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
