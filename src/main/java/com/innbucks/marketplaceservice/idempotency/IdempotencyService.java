package com.innbucks.marketplaceservice.idempotency;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Claim-row idempotency over the V1 {@code idempotency_record} table
 * (InnBucksMiddleware2.0 design, adapted from Spring Data JDBC to a plain
 * {@link JdbcTemplate} in this JPA app). Pattern:
 *
 * <ol>
 *   <li>Caller supplies an Idempotency-Key header per logical request; the
 *       order flow namespaces it per buyer via {@link #namespaced(String, String)}
 *       so a client can never collide with (or replay) another caller's key.</li>
 *   <li>The first invocation CLAIMS the key with an
 *       {@code INSERT … ON CONFLICT DO NOTHING} row ({@code status = 0}
 *       sentinel) BEFORE any work runs, then stores the response on the same
 *       row via {@link #complete(String, int, String)}. The claim is what
 *       closes the concurrent double-execute race: exactly one caller wins the
 *       insert and reserves stock.</li>
 *   <li>Retries with the same key + same request replay the cached response
 *       with its ORIGINAL status; a retry racing the winner sees
 *       {@link ClaimResult.InFlight} (→ 409) and retries with the same key.</li>
 *   <li>The same key + a different request fingerprint is
 *       {@link ClaimResult.Mismatch} (→ 422) — never executed.</li>
 * </ol>
 *
 * <p>If the work throws, the caller should {@link #release(String)} the claim so
 * a retry can re-execute. A crash between claim and store leaves a stale claim;
 * claims older than {@link #IN_PROGRESS_GRACE} are treated as dead and taken
 * over. The partial unique index on {@code market_order.idempotency_key} is the
 * DB backstop: even a takeover racing a not-actually-dead owner cannot create a
 * second order for the key.
 */
@Slf4j
@Service
public class IdempotencyService {

    /** Sentinel status meaning "claimed, work in flight". Real completions store
     *  the HTTP status, which is never 0. */
    static final int IN_PROGRESS = 0;

    /**
     * A claim older than this whose work never stored a response is presumed
     * crashed (the work either completed or died with the JVM); a new caller may
     * take it over. Must comfortably exceed the slowest order-creation path so a
     * live-but-slow request is never usurped.
     */
    static final Duration IN_PROGRESS_GRACE = Duration.ofSeconds(60);

    private static final String CLAIM_SQL = """
            INSERT INTO idempotency_record
                (key_hash, request_hash, status, response_body, created_at, updated_at)
            VALUES (?, ?, %d, NULL, ?, ?)
            ON CONFLICT (key_hash) DO NOTHING
            """.formatted(IN_PROGRESS);

    private static final String SELECT_SQL = """
            SELECT request_hash, status, response_body, created_at
              FROM idempotency_record
             WHERE key_hash = ?
            """;

    // Guarded takeover: only a still-in-flight claim older than the stale
    // threshold is reset, so a racing taker (or an owner that just completed)
    // makes the update a 0-row no-op instead of clobbering a stored response.
    private static final String TAKE_OVER_STALE_SQL = """
            UPDATE idempotency_record
               SET created_at = ?, updated_at = ?
             WHERE key_hash = ? AND status = %d AND created_at < ?
            """.formatted(IN_PROGRESS);

    private static final String COMPLETE_SQL = """
            UPDATE idempotency_record
               SET status = ?, response_body = ?, updated_at = ?
             WHERE key_hash = ?
            """;

    private static final String RELEASE_SQL = """
            DELETE FROM idempotency_record
             WHERE key_hash = ? AND status = %d
            """.formatted(IN_PROGRESS);

    private final JdbcTemplate jdbcTemplate;

    // Built from the DataSource directly (fleet shape, see SchedulerLockConfig)
    // rather than relying on a JdbcTemplate bean from Boot's modular auto-config.
    public IdempotencyService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * Namespaces a caller-supplied Idempotency-Key. Inbound keys are
     * client-chosen and would otherwise be GLOBALLY scoped — a buggy or
     * malicious client reusing another buyer's key could replay that buyer's
     * cached response. Hashing the scope (buyer UUID) together with the raw key
     * makes collisions across scopes impossible while keeping retries by the
     * same caller deterministic. The 64-hex output is used verbatim as the
     * {@code idempotency_record} key AND {@code market_order.idempotency_key}.
     */
    public static String namespaced(String scope, String rawKey) {
        if (scope == null || scope.isBlank() || rawKey == null || rawKey.isBlank()) {
            throw new IllegalArgumentException("scope and rawKey must be non-blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Unit-separator between the parts: without it ("ab","c") and
            // ("a","bc") would hash identically across scopes.
            byte[] hashed = digest.digest((scope + "\u001F" + rawKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /**
     * Claim {@code keyHash} for execution. See {@link ClaimResult} for the
     * outcomes the caller must handle. {@code requestHash} is the caller's
     * canonical fingerprint of the request body (SHA-256 hex) — it decides
     * replay vs mismatch.
     */
    public ClaimResult claim(String keyHash, String requestHash) {
        // Two passes at most: the second only after a released/vanished claim
        // (row deleted between our failed insert and the lookup).
        for (int attempt = 0; attempt < 2; attempt++) {
            Instant now = Instant.now();
            if (jdbcTemplate.update(CLAIM_SQL, keyHash, requestHash,
                    Timestamp.from(now), Timestamp.from(now)) == 1) {
                return new ClaimResult.New();
            }

            ClaimRow row = lookup(keyHash).orElse(null);
            if (row == null) {
                // Claim lost AND row gone: the concurrent owner's work threw and
                // released. Loop once more to claim afresh.
                continue;
            }
            if (!row.requestHash().equals(requestHash)) {
                return new ClaimResult.Mismatch();
            }
            if (row.status() != IN_PROGRESS) {
                log.debug("Idempotency key {} -> replaying cached response (status={})",
                        keyHash, row.status());
                return new ClaimResult.Replay(row.status(), row.responseBody());
            }
            Instant staleBefore = now.minus(IN_PROGRESS_GRACE);
            if (row.createdAt().isBefore(staleBefore)
                    && jdbcTemplate.update(TAKE_OVER_STALE_SQL,
                            Timestamp.from(now), Timestamp.from(now),
                            keyHash, Timestamp.from(staleBefore)) == 1) {
                // The key hash is not a secret (SHA-256 of scope+key) — safe to log.
                log.warn("Idempotency key {}: took over stale in-flight claim from {}",
                        keyHash, row.createdAt());
                return new ClaimResult.New();
            }
            return new ClaimResult.InFlight();
        }
        return new ClaimResult.InFlight();
    }

    /**
     * Store the outcome on the claim row so replays return the ORIGINAL status
     * + body. {@code status} is the HTTP status the first execution answered
     * with (201 for a created order, but a stored 422 etc. replays just as
     * faithfully — an outcome the client already saw must not change on retry).
     */
    public void complete(String keyHash, int status, String responseBody) {
        if (status == IN_PROGRESS) {
            throw new IllegalArgumentException("status 0 is reserved for the in-progress sentinel");
        }
        int updated = jdbcTemplate.update(COMPLETE_SQL,
                status, responseBody, Timestamp.from(Instant.now()), keyHash);
        if (updated != 1) {
            // The claim vanished (released, or taken over after a >60s stall) —
            // the response still went to the client; only replay is lost.
            log.warn("Idempotency key {}: complete() updated {} rows (claim lost?)",
                    keyHash, updated);
        }
    }

    /**
     * Release a still-in-flight claim after the work threw, so a retry can
     * re-execute (middleware semantics). Guarded on the status-0 sentinel: a
     * completed record is never deleted by a late/racing release.
     */
    public void release(String keyHash) {
        try {
            jdbcTemplate.update(RELEASE_SQL, keyHash);
        } catch (RuntimeException ex) {
            // Worst case the claim goes stale and is taken over after the grace.
            log.error("Failed to release idempotency claim for key {}", keyHash, ex);
        }
    }

    private Optional<ClaimRow> lookup(String keyHash) {
        List<ClaimRow> rows = jdbcTemplate.query(SELECT_SQL,
                (rs, n) -> new ClaimRow(
                        rs.getString("request_hash"),
                        rs.getInt("status"),
                        rs.getString("response_body"),
                        rs.getTimestamp("created_at").toInstant()),
                keyHash);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private record ClaimRow(String requestHash, int status, String responseBody,
                            Instant createdAt) {
    }
}
