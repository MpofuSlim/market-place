package com.innbucks.marketplaceservice.support;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;

/**
 * Base class for {@code *IT} integration tests that boot the full Spring
 * context against a REAL Postgres (InnRewards' {@code PostgresIntegrationTestBase}
 * pattern) — for everything H2 can't fake faithfully: the V1 partial unique
 * index, {@code ON CONFLICT DO NOTHING} claims, atomic stock UPDATEs, the
 * {@code SELECT … FOR UPDATE} audit chain head, and ShedLock's lock table.
 * Flyway runs the production V1 migration; Hibernate only validates — the
 * schema under test is the schema in prod.
 *
 * <p>One container is shared across every extending class (static field +
 * manual start, no {@code @Container} — the JUnit extension would stop a
 * static container after the FIRST class and never restart it for the next).
 * All subclasses share the same {@code @SpringBootTest} + {@code test}-profile
 * configuration, so Spring's test context cache boots the app once per build.
 *
 * <p>Requires Docker; without it these classes are SKIPPED (never red) — CI
 * runs them via Failsafe during {@code verify}.
 *
 * <p>Every test method starts from clean tables: {@link #resetDatabaseState()}
 * truncates the business tables and re-seeds the audit chain head to its V1
 * genesis state (last_chain_hmac NULL), so the audit chain each test writes is
 * self-consistent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("com.innbucks.marketplaceservice.support.PostgresTestContainer#isDockerAvailable")
public abstract class PostgresTestContainer {

    // Static + reused across test classes; Testcontainers' Ryuk reaps it on
    // JVM exit. Same database name as production so the JDBC URL reads true.
    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("marketplace_service")
                    .withUsername("marketplace")
                    .withPassword("marketplace");

    @BeforeAll
    static void startContainer() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    /**
     * JUnit {@code @EnabledIf} entrypoint — short-circuits the whole IT class
     * when Docker isn't reachable (belt to {@code disabledWithoutDocker}'s
     * braces), so a dev box without Docker skips instead of erroring.
     */
    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    /** Built from the DataSource directly (fleet shape — Boot 4's modular
     *  auto-config is not guaranteed to publish a JdbcTemplate bean here). */
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabaseState() {
        jdbc = new JdbcTemplate(dataSource);
        // One statement so the FKs (market_order_item -> market_order,
        // listing_image/listing_review/listing_favorite/listing_report ->
        // listing) never bite; RESTART IDENTITY resets the BIGSERIAL journals.
        // The migration-seeded category table is deliberately NOT truncated —
        // it is runtime-read-only reference data.
        jdbc.execute("""
                TRUNCATE TABLE market_order_item, market_order_event, market_order,
                               listing_image, listing_review, listing_favorite,
                               listing_report, listing, idempotency_record, audit_events
                RESTART IDENTITY""");
        // Back to the V1 genesis head so each test's audit chain is
        // self-consistent (AuditService links every row to this head).
        jdbc.execute("UPDATE audit_chain_head SET last_chain_hmac = NULL, last_event_id = NULL");
    }
}
