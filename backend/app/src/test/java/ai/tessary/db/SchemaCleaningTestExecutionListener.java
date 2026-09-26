// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Empties the schema after every test class, so classes sharing a Spring context cannot read each other's rows. They
 * were once isolated by accident, one database per context; collapsing contexts collapsed the databases, and this
 * listener replaces the accident.
 *
 * <p>Tables come from {@code pg_tables} at run time, so the list cannot drift from Liquibase. Held back: Liquibase's
 * bookkeeping, and whatever boot-time seeding wrote (the price book is imported once per context), measured on the
 * first class to use a context.
 *
 * <p>Registered for every context in {@code META-INF/spring.factories}, alongside the framework defaults.
 */
public class SchemaCleaningTestExecutionListener implements TestExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(SchemaCleaningTestExecutionListener.class);

    /** Both mean a worker got there first, not that the SQL is wrong. */
    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40P01", "55P03");

    private static final int MAX_ATTEMPTS = 10;
    private static final long LOCK_TIMEOUT_MS = 2_000;
    private static final long RETRY_BACKOFF_MS = 250;

    private static final Set<String> LIQUIBASE_TABLES = Set.of("databasechangelog", "databasechangeloglock");

    /** Keyed by the per-context database URL. */
    private static final Map<String, Set<String>> SEEDED_TABLES = new ConcurrentHashMap<>();

    @Override
    public void beforeTestClass(TestContext testContext) {
        // Loading the context here is the point: on its first class the database holds only what Liquibase and boot
        // left, the one moment reference data can be told apart.
        ApplicationContext context = testContext.getApplicationContext();
        DataSource dataSource = dataSourceOf(context);
        if (dataSource == null) {
            return;
        }
        SEEDED_TABLES.computeIfAbsent(databaseKey(context), key -> readNonEmptyTables(dataSource));
    }

    @Override
    public void afterTestClass(TestContext testContext) {
        ApplicationContext context = testContext.getApplicationContext();
        DataSource dataSource = dataSourceOf(context);
        if (dataSource == null) {
            return;
        }
        Set<String> seeded = SEEDED_TABLES.getOrDefault(databaseKey(context), Set.of());
        List<String> toClear = readAllTables(dataSource).stream()
                .filter(table -> !LIQUIBASE_TABLES.contains(table))
                .filter(table -> !seeded.contains(table))
                .toList();
        if (toClear.isEmpty()) {
            return;
        }
        String sql = toClear.stream()
                .map(table -> "\"public\".\"" + table + "\"")
                .collect(Collectors.joining(", ", "TRUNCATE TABLE ", " RESTART IDENTITY CASCADE"));
        truncateWithRetry(dataSource, sql, testContext.getTestClass());
    }

    /**
     * Retries the TRUNCATE because a shared context's scheduled workers keep ticking. Postgres breaks a lock-order
     * deadlock itself, but a lock holder waiting on the JVM forms a cycle no detector spans (on CI every thread
     * stalled for the full 30s), so a short timeout gives up early and frees the workers.
     *
     * <p>A statement that never finishes cannot be outlasted (a {@code markOrphanPaths} pass once ran 16 minutes), so
     * a lost race also ends every session whose transaction is older than the lock wait; they are background workers,
     * and they reconnect.
     */
    private static void truncateWithRetry(DataSource dataSource, String sql, Class<?> testClass) {
        for (int attempt = 1; ; attempt++) {
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                requireThrowawayContainer(connection);
                statement.execute("SET lock_timeout = '" + LOCK_TIMEOUT_MS + "ms'");
                statement.execute(sql);
                return;
            } catch (SQLException e) {
                if (!RETRYABLE_SQL_STATES.contains(e.getSQLState()) || attempt == MAX_ATTEMPTS) {
                    throw new IllegalStateException(
                            "failed to clean the test schema after " + testClass + " in " + attempt
                                    + " attempt(s); other sessions: " + describeOtherSessions(dataSource),
                            e);
                }
                log.warn(
                        "schema clean after {} lost a lock race on attempt {} ({}); other sessions: {}; terminated: {}",
                        testClass.getSimpleName(),
                        attempt,
                        e.getSQLState(),
                        describeOtherSessions(dataSource),
                        terminateLongTransactions(dataSource));
                sleep(RETRY_BACKOFF_MS * attempt);
            }
        }
    }

    private static String describeOtherSessions(DataSource dataSource) {
        List<String> sessions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT pid, state, now() - xact_start, wait_event_type, left(query, 200)
                        FROM pg_stat_activity
                        WHERE datname = current_database() AND pid <> pg_backend_pid() AND xact_start IS NOT NULL
                        ORDER BY xact_start""")) {
            while (rows.next()) {
                sessions.add("[pid=" + rows.getInt(1) + " state=" + rows.getString(2) + " xact_age=" + rows.getString(3)
                        + " wait=" + rows.getString(4) + " query=" + rows.getString(5) + "]");
            }
        } catch (SQLException e) {
            return "unavailable (" + e.getMessage() + ")";
        }
        return sessions.isEmpty() ? "none in a transaction" : String.join(" ", sessions);
    }

    private static String terminateLongTransactions(DataSource dataSource) {
        List<Integer> terminated = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            requireThrowawayContainer(connection);
            try (ResultSet rows = statement.executeQuery("SELECT pid FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND pid <> pg_backend_pid()"
                    + " AND xact_start < now() - interval '" + LOCK_TIMEOUT_MS + " milliseconds'"
                    + " AND pg_terminate_backend(pid)")) {
                while (rows.next()) {
                    terminated.add(rows.getInt(1));
                }
            }
        } catch (SQLException e) {
            return "unavailable (" + e.getMessage() + ")";
        }
        return terminated.isEmpty() ? "none" : terminated.toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retrying the schema clean", e);
        }
    }

    /**
     * Refuses to truncate anything but the throwaway container. Today's safety is positional (the initializer
     * overrides {@code tessary.jdbc-url}), so this checks the connection's own URL, which is what is about to be
     * emptied.
     */
    private static void requireThrowawayContainer(Connection connection) throws SQLException {
        String url = connection.getMetaData().getURL();
        String expected = TestPostgres.urlPrefix();
        if (url == null || !url.startsWith(expected)) {
            throw new IllegalStateException("refusing to TRUNCATE: connected to " + url
                    + ", which is not the throwaway Testcontainers database at " + expected
                    + ". This listener empties every non-seed table and must never point anywhere else.");
        }
    }

    private static String databaseKey(ApplicationContext context) {
        String url = context.getEnvironment().getProperty("tessary.jdbc-url");
        return url == null ? context.getId() : url;
    }

    private static @Nullable DataSource dataSourceOf(ApplicationContext context) {
        return context.getBeanNamesForType(DataSource.class).length == 0 ? null : context.getBean(DataSource.class);
    }

    private static List<String> readAllTables(DataSource dataSource) {
        List<String> tables = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to list the test schema's tables", e);
        }
        return tables;
    }

    private static Set<String> readNonEmptyTables(DataSource dataSource) {
        Set<String> nonEmpty = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            for (String table : readAllTables(dataSource)) {
                if (LIQUIBASE_TABLES.contains(table)) {
                    continue;
                }
                try (ResultSet rows =
                        statement.executeQuery("SELECT EXISTS (SELECT 1 FROM \"public\".\"" + table + "\")")) {
                    if (rows.next() && rows.getBoolean(1)) {
                        nonEmpty.add(table);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to read the boot-seeded tables", e);
        }
        return nonEmpty;
    }
}
