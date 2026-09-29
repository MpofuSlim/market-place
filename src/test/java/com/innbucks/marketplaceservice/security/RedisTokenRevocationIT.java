package com.innbucks.marketplaceservice.security;

import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Session revocation over HTTP against a REAL Redis — the only place it runs
 * that way. Every other IT points Redis at a closed port (application-test.yaml),
 * so there {@link RevokedTokenDenylist} and {@link TokenVersionStore} only ever
 * take their fail-open branches and a filter that ignored them would pass.
 *
 * <p>The entries are written EXACTLY as user-service writes them into the
 * shared fleet Redis: {@code auth:revoked:<sha256HexLower(token)> -> "1"} (with
 * a TTL) on logout, and {@code auth:tokenver:<userUuid> -> "<decimal>"} when a
 * newer login or a password change supersedes older sessions. The key scheme
 * is re-derived here independently rather than borrowed from the readers, so a
 * reader drifting from the writer's bytes fails this test.
 *
 * <p>Own Spring context (the extra Redis properties change the cache key); the
 * shared Postgres container underneath is the same one every IT uses.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisTokenRevocationIT extends PostgresTestContainer {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        if (!REDIS.isRunning()) {
            REDIS.start();
        }
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
    }

    /** A CUSTOMER-only endpoint that answers 200 for any buyer: a protected path. */
    private static final String PROTECTED = "/marketplace/cart";

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Autowired
    private StringRedisTemplate redis;

    // --- the writer's side, mirrored from user-service ---------------------

    private void revoke(String token) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        redis.opsForValue().set("auth:revoked:" + hash, "1", Duration.ofMinutes(15));
    }

    private void publishTokenVersion(UUID userUuid, String version) {
        redis.opsForValue().set("auth:tokenver:" + userUuid, version);
    }

    private String customerToken(UUID user, long tokenVersion) {
        return TestJwts.forUser(user).role("CUSTOMER")
                .loginIdentifier(user + "@example.com") // fleet shape: sub is the login id
                .tokenVersion(tokenVersion)
                .sign(jwtSecret);
    }

    private void expectServed(String token) throws Exception {
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    private void expectRefused(String token) throws Exception {
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Invalid or missing token"));
    }

    @Test
    void aCurrentTokenIsServed() throws Exception {
        UUID user = UUID.randomUUID();
        publishTokenVersion(user, "3");

        expectServed(customerToken(user, 3));
    }

    @Test
    void aLoggedOutTokenIsRefusedTheMomentItIsDenylisted() throws Exception {
        UUID user = UUID.randomUUID();
        String token = customerToken(user, 1);
        expectServed(token);

        revoke(token);

        expectRefused(token);
        // Only THAT token: the same user's next session (a different token —
        // two mints in one second would otherwise be byte-identical) is untouched.
        expectServed(customerToken(user, 2));
    }

    @Test
    void aSupersededTokenIsRefusedAndTheCurrentOneServed() throws Exception {
        UUID user = UUID.randomUUID();
        String older = customerToken(user, 4);
        expectServed(older);

        publishTokenVersion(user, "5"); // a newer login / password change

        expectRefused(older);
        expectServed(customerToken(user, 5));
        expectServed(customerToken(user, 6));
    }

    @Test
    void anotherUsersVersionDoesNotTouchThisOne() throws Exception {
        UUID user = UUID.randomUUID();
        publishTokenVersion(UUID.randomUUID(), "99");

        expectServed(customerToken(user, 1));
    }

    @Test
    void anUnreadablePublishedVersionFailsOpen() throws Exception {
        // The store's fail-open, on real Redis data rather than a dead socket:
        // a value it cannot parse enforces nothing instead of 401ing the user.
        UUID user = UUID.randomUUID();
        publishTokenVersion(user, "not-a-number");

        expectServed(customerToken(user, 1));
    }

    @Test
    void aRevokedTokenStillBrowsesThePublicCatalogue() throws Exception {
        // The filter is non-rejecting by design: a refused token leaves the
        // request unauthenticated, and only a PROTECTED path turns that into a
        // 401. A stale token must not hard-fail a public page.
        UUID user = UUID.randomUUID();
        String token = customerToken(user, 1);
        revoke(token);

        mockMvc.perform(get("/marketplace/catalog").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        expectRefused(token);
    }

    @Test
    void aTokenPendingAPasswordChangeIsRefused() throws Exception {
        UUID user = UUID.randomUUID();
        publishTokenVersion(user, "1");

        expectRefused(TestJwts.forUser(user).role("CUSTOMER").tokenVersion(1)
                .mustChangePassword().sign(jwtSecret));
    }
}
