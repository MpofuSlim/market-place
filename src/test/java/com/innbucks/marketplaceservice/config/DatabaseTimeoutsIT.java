package com.innbucks.marketplaceservice.config;

import com.innbucks.marketplaceservice.audit.AuditChainHeadRepository;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the database timeouts on APPLICATION connections, and Flyway's
 * exemption from them, against real Postgres.
 *
 * <p>Hikari's {@code connection-timeout} only bounds acquiring a connection;
 * these bound what happens on one. The values ride PgJDBC's {@code options}
 * startup parameter, so the assertions read them back from the server rather
 * than from our own configuration — a driver or pool upgrade that silently
 * stopped passing them would fail here. The fail-fast cases narrow the timeout
 * with {@code SET LOCAL} inside their own transaction (reverted at rollback, so
 * no pooled connection is left altered) instead of waiting out the production
 * values.
 */
class DatabaseTimeoutsIT extends PostgresTestContainer {

    private static final List<String> TIMEOUTS = List.of(
            "statement_timeout", "lock_timeout", "idle_in_transaction_session_timeout");

    private static final Pattern OPTION = Pattern.compile("-c\\s+(\\w+)=(\\S+)");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AuditChainHeadRepository chainHeadRepository;

    @Test
    @DisplayName("Every pooled application connection carries the configured timeouts, set at connection startup")
    void everyPooledConnectionCarriesTheConfiguredTimeouts() throws SQLException {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        Map<String, String> configured = configuredOptions(hikari);

        // The production defaults, pinned: a change here is a deliberate one.
        assertThat(configured).containsExactlyInAnyOrderEntriesOf(Map.of(
                "statement_timeout", "30000",
                "lock_timeout", "10000",
                "idle_in_transaction_session_timeout", "60000"));
        assertThat(hikari.getDataSourceProperties().getProperty("socketTimeout")).isEqualTo("60");

        // Hold EVERY connection the pool can hand out at once, so none of them
        // (including any the context used while booting) escapes the check.
        List<Connection> held = new ArrayList<>();
        try {
            for (int i = 0; i < hikari.getMaximumPoolSize(); i++) {
                held.add(dataSource.getConnection());
            }
            for (Connection c : held) {
                Map<String, String[]> settings = settings(c);
                for (String name : TIMEOUTS) {
                    assertThat(settings.get(name)[0]).as(name).isEqualTo(configured.get(name));
                    // "client" = the startup packet, not a session SET: a RESET
                    // or DISCARD ALL returns to it rather than to no timeout.
                    assertThat(settings.get(name)[1]).as(name + " source").isEqualTo("client");
                }
                // Client side: must sit above statement_timeout, or a slow but
                // legal statement is cut by the socket and the connection lost.
                assertThat(c.getNetworkTimeout()).isEqualTo(60_000);
                assertThat(c.getNetworkTimeout())
                        .isGreaterThan(Integer.parseInt(configured.get("statement_timeout")));
            }
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
    }

    @Test
    @DisplayName("A statement running past statement_timeout is cancelled by the server, not left hanging")
    void aStatementPastTheTimeoutIsCancelledNotHung() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        long started = System.nanoTime();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL statement_timeout = 250");
            jdbc.queryForObject("SELECT pg_sleep(20)::text", String.class);
        }))
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> assertThat(sqlState(ex)).isEqualTo("57014")); // query_canceled

        assertThat(elapsedMillis(started)).isLessThan(5_000);
        // SET LOCAL died with the rollback: the pool is untouched.
        assertThat(jdbc.queryForObject("SELECT setting FROM pg_settings WHERE name = 'statement_timeout'",
                String.class)).isEqualTo("30000");
    }

    @Test
    @DisplayName("A pessimistic lock wait past lock_timeout fails fast as a lock failure (the audit chain head)")
    void aLockWaitPastTheTimeoutFailsFast() throws Exception {
        UnpooledLockHolder holder = new UnpooledLockHolder();
        try {
            // Another session holds the audit chain head, as a stuck audit
            // writer would.
            holder.lock("SELECT id FROM audit_chain_head WHERE id = 1 FOR UPDATE");

            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            long started = System.nanoTime();
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                jdbc.execute("SET LOCAL lock_timeout = 250");
                chainHeadRepository.lockHead();
            }))
                    .isInstanceOf(PessimisticLockingFailureException.class)
                    .satisfies(ex -> assertThat(sqlState(ex)).isEqualTo("55P03")); // lock_not_available

            assertThat(elapsedMillis(started)).isLessThan(5_000);
        } finally {
            holder.release();
        }
    }

    @Test
    @DisplayName("Flyway migrates on its own unpooled connections with every timeout off, even against a role default")
    void flywayIsExemptFromTheTimeouts() throws SQLException {
        // Its own DataSource: a session SET on Flyway's connection can never be
        // handed back into the application pool.
        DataSource flywayDataSource = flyway.getConfiguration().getDataSource();
        assertThat(flywayDataSource).isNotSameAs(dataSource);
        assertThat(flywayDataSource).isNotInstanceOf(HikariDataSource.class);

        // A role default makes the assertion discriminating: without the
        // init-sqls a new Flyway session would inherit these values.
        jdbc.execute("ALTER ROLE CURRENT_USER SET statement_timeout = '1234ms'");
        jdbc.execute("ALTER ROLE CURRENT_USER SET lock_timeout = '1234ms'");
        jdbc.execute("ALTER ROLE CURRENT_USER SET idle_in_transaction_session_timeout = '1234ms'");
        try {
            try (Connection raw = flywayDataSource.getConnection()) {
                assertThat(settings(raw).get("statement_timeout")[0]).isEqualTo("1234");
            }

            Map<String, String> seenByMigration = new HashMap<>();
            MigrateResult result = Flyway.configure()
                    .configuration(flyway.getConfiguration())
                    .callbacks(recordTimeoutsBeforeMigrate(seenByMigration))
                    .load()
                    .migrate();

            assertThat(result.migrationsExecuted).isZero(); // the schema is current
            assertThat(seenByMigration).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "statement_timeout", "0",
                    "lock_timeout", "0",
                    "idle_in_transaction_session_timeout", "0"));
        } finally {
            jdbc.execute("ALTER ROLE CURRENT_USER RESET statement_timeout");
            jdbc.execute("ALTER ROLE CURRENT_USER RESET lock_timeout");
            jdbc.execute("ALTER ROLE CURRENT_USER RESET idle_in_transaction_session_timeout");
        }
    }

    // ------------------------------------------------------------------------

    private static Map<String, String> configuredOptions(HikariDataSource hikari) {
        String options = hikari.getDataSourceProperties().getProperty("options");
        assertThat(options).as("PgJDBC options on the application pool").isNotBlank();
        Map<String, String> parsed = new LinkedHashMap<>();
        Matcher m = OPTION.matcher(options);
        while (m.find()) {
            parsed.put(m.group(1), m.group(2));
        }
        return parsed;
    }

    /** name → {setting in the GUC's base unit (ms), source}. */
    private static Map<String, String[]> settings(Connection c) throws SQLException {
        Map<String, String[]> out = new HashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name, setting, source FROM pg_settings WHERE name IN "
                     + "('statement_timeout', 'lock_timeout', 'idle_in_transaction_session_timeout')")) {
            while (rs.next()) {
                out.put(rs.getString(1), new String[]{rs.getString(2), rs.getString(3)});
            }
        }
        return out;
    }

    private static Callback recordTimeoutsBeforeMigrate(Map<String, String> into) {
        return new Callback() {
            @Override
            public boolean supports(Event event, Context context) {
                return event == Event.BEFORE_MIGRATE;
            }

            @Override
            public boolean canHandleInTransaction(Event event, Context context) {
                return true;
            }

            @Override
            public void handle(Event event, Context context) {
                try {
                    settings(context.getConnection()).forEach((name, value) -> into.put(name, value[0]));
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public String getCallbackName() {
                return "record-timeouts";
            }
        };
    }

    private static String sqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /**
     * A session OUTSIDE the application pool that takes a row lock and holds
     * it, so the pool under test is never the thing blocked or altered.
     */
    private final class UnpooledLockHolder {
        private Connection connection;

        void lock(String sql) throws SQLException {
            connection = flyway.getConfiguration().getDataSource().getConnection();
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                st.executeQuery(sql).close();
            }
        }

        void release() throws SQLException {
            if (connection != null) {
                connection.rollback();
                connection.close();
            }
        }
    }
}
