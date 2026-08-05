package com.innbucks.marketplaceservice.idempotency;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link IdempotencyService}'s claim-row semantics against a REAL
 * Postgres (the {@code ON CONFLICT DO NOTHING} claim and the guarded
 * stale-takeover UPDATE are Postgres SQL — a mocked JdbcTemplate could only
 * restate the code). Runs the actual V1 Flyway migration so the table shape
 * is the production one. No Spring context — the service is constructed
 * directly on the container's DataSource.
 */
@Testcontainers
class IdempotencyServiceIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("marketplace_service");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    private IdempotencyService service;

    private static final String REQUEST_HASH = "b".repeat(64);
    private static final String OTHER_REQUEST_HASH = "c".repeat(64);

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE TABLE idempotency_record");
        service = new IdempotencyService(dataSource);
    }

    private static String key(String raw) {
        return IdempotencyService.namespaced("buyer-uuid", raw);
    }

    private void backdateClaim(String keyHash, Duration age) {
        jdbc.update("UPDATE idempotency_record SET created_at = ? WHERE key_hash = ?",
                Timestamp.from(Instant.now().minus(age)), keyHash);
    }

    @Test
    void freshKeyIsClaimedAsNew() {
        String keyHash = key("fresh");

        assertThat(service.claim(keyHash, REQUEST_HASH)).isInstanceOf(ClaimResult.New.class);

        // The claim row exists with the status-0 in-flight sentinel.
        assertThat(jdbc.queryForObject(
                "SELECT status FROM idempotency_record WHERE key_hash = ?",
                Integer.class, keyHash)).isZero();
    }

    @Test
    void replayReturnsOriginalStatusAndBody() {
        String keyHash = key("replayed");
        String body = "{\"orderRef\":\"MKT-abc123def456\"}";
        service.claim(keyHash, REQUEST_HASH);
        service.complete(keyHash, 201, body);

        ClaimResult replayed = service.claim(keyHash, REQUEST_HASH);

        assertThat(replayed).isInstanceOf(ClaimResult.Replay.class);
        ClaimResult.Replay replay = (ClaimResult.Replay) replayed;
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.responseBody()).isEqualTo(body);
    }

    @Test
    void storedErrorOutcomeReplaysJustAsFaithfully() {
        // An outcome the client already saw must not change on retry — even a 422.
        String keyHash = key("stored-error");
        service.claim(keyHash, REQUEST_HASH);
        service.complete(keyHash, 422, "{\"code\":\"OUT_OF_STOCK\"}");

        ClaimResult replayed = service.claim(keyHash, REQUEST_HASH);

        assertThat(replayed).isInstanceOf(ClaimResult.Replay.class);
        assertThat(((ClaimResult.Replay) replayed).status()).isEqualTo(422);
    }

    @Test
    void concurrentSameKeyClaimWithinGraceIsInFlight() {
        String keyHash = key("racing");
        service.claim(keyHash, REQUEST_HASH);

        // Second caller, same key + same request, winner not yet completed.
        assertThat(service.claim(keyHash, REQUEST_HASH))
                .isInstanceOf(ClaimResult.InFlight.class);
    }

    @Test
    void staleInFlightClaimIsTakenOverAfterGrace() {
        String keyHash = key("crashed");
        service.claim(keyHash, REQUEST_HASH);
        backdateClaim(keyHash, Duration.ofMinutes(2));    // > 60s IN_PROGRESS_GRACE

        assertThat(service.claim(keyHash, REQUEST_HASH)).isInstanceOf(ClaimResult.New.class);

        // Takeover reuses the row (created_at refreshed), never inserts a second.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE key_hash = ?",
                Integer.class, keyHash)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT created_at FROM idempotency_record WHERE key_hash = ?",
                Timestamp.class, keyHash).toInstant())
                .isAfter(Instant.now().minusSeconds(30));
    }

    @Test
    void claimJustUnderTheGraceIsNotTakenOver() {
        String keyHash = key("slow-but-alive");
        service.claim(keyHash, REQUEST_HASH);
        backdateClaim(keyHash, Duration.ofSeconds(50));   // < 60s: owner may be alive

        assertThat(service.claim(keyHash, REQUEST_HASH))
                .isInstanceOf(ClaimResult.InFlight.class);
    }

    @Test
    void takeoverNeverClobbersACompletedRecord() {
        // Old but COMPLETED rows replay — the stale takeover only targets the
        // status-0 sentinel.
        String keyHash = key("old-completed");
        service.claim(keyHash, REQUEST_HASH);
        service.complete(keyHash, 201, "{\"orderRef\":\"MKT-old\"}");
        backdateClaim(keyHash, Duration.ofDays(1));

        ClaimResult result = service.claim(keyHash, REQUEST_HASH);

        assertThat(result).isInstanceOf(ClaimResult.Replay.class);
        assertThat(((ClaimResult.Replay) result).status()).isEqualTo(201);
    }

    @Test
    void sameKeyDifferentRequestHashIsMismatch() {
        String keyHash = key("reused-wrong");
        service.claim(keyHash, REQUEST_HASH);

        // Mismatch wins over in-flight AND over a stored completion: the key is
        // being misused, never executed and never replayed.
        assertThat(service.claim(keyHash, OTHER_REQUEST_HASH))
                .isInstanceOf(ClaimResult.Mismatch.class);

        service.complete(keyHash, 201, "{}");
        assertThat(service.claim(keyHash, OTHER_REQUEST_HASH))
                .isInstanceOf(ClaimResult.Mismatch.class);
    }

    @Test
    void releasedClaimCanBeReclaimed() {
        String keyHash = key("work-threw");
        service.claim(keyHash, REQUEST_HASH);
        service.release(keyHash);

        assertThat(service.claim(keyHash, REQUEST_HASH)).isInstanceOf(ClaimResult.New.class);
    }

    @Test
    void releaseIsGuardedOnTheInFlightSentinel() {
        // A late/racing release must not delete a stored response.
        String keyHash = key("late-release");
        service.claim(keyHash, REQUEST_HASH);
        service.complete(keyHash, 201, "{\"orderRef\":\"MKT-kept\"}");
        service.release(keyHash);

        ClaimResult result = service.claim(keyHash, REQUEST_HASH);
        assertThat(result).isInstanceOf(ClaimResult.Replay.class);
        assertThat(((ClaimResult.Replay) result).responseBody()).contains("MKT-kept");
    }
}
