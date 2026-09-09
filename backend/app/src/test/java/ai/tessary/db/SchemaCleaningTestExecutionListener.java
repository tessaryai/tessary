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
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Empties the schema after every test class, so classes sharing a Spring context cannot read each
 * other's rows.
 *
 * <p>They used to be isolated by accident. Each of ~80 classes declared its own byte-identical
 * {@code @DynamicPropertySource}, and because {@code DynamicPropertiesContextCustomizer} equality
 * compares the {@code Set<Method>} it was built from, each got its own context cache key — and
 * {@link TestcontainersPostgresInitializer} hands every context a freshly created database. Once
 * {@code ai.tessary.config.TestSecretKeyInitializer} collapsed those contexts, the databases
 * collapsed with them, and only one class in the suite is {@code @Transactional}. This listener is
 * the isolation that replaces the accident.
 *
 * <p>The table list is read from {@code pg_tables} at run time rather than written down, so it
 * cannot drift away from the Liquibase changelog. Two categories are held back:
 *
 * <ul>
 *   <li>Liquibase's own bookkeeping — truncating {@code databasechangelog} would make the changelog
 *       look unapplied to anything that inspected it.
 *   <li>Whatever boot-time seeding wrote. {@code PriceBookImporter} imports the rate books on
 *       {@code ApplicationReadyEvent}; that runs once per context, so a class that truncated
 *       {@code model_price} would leave every later class in the same context with no price book.
 *       The held-back set is measured rather than listed: the first class to use a context reads
 *       which tables the boot left non-empty, and those are the reference data.
 * </ul>
 *
 * <p>Registered for every context in {@code src/test/resources/META-INF/spring.factories}. A
 * listener declared there is added to the framework defaults rather than replacing them.
 */
public class SchemaCleaningTestExecutionListener implements TestExecutionListener {

    private static final Set<String> LIQUIBASE_TABLES = Set.of("databasechangelog", "databasechangeloglock");

    /** Keyed by the per-context database URL, which {@link TestcontainersPostgresInitializer} makes unique. */
    private static final Map<String, Set<String>> SEEDED_TABLES = new ConcurrentHashMap<>();

    @Override
    public void beforeTestClass(TestContext testContext) {
        // Forces the context to load if it has not already, which is the point: on the first class
        // to use a context the database still holds exactly what Liquibase and the boot listeners
        // left, and that is the only moment reference data can be told apart from test rows.
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
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            requireThrowawayContainer(connection);
            // A shared context keeps its @Scheduled workers ticking between classes, so the ACCESS
            // EXCLUSIVE locks TRUNCATE takes can collide with one mid-statement. Bound the wait
            // rather than hang the suite.
            statement.execute("SET lock_timeout = '30s'");
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to clean the test schema after " + testContext.getTestClass(), e);
        }
    }

    /**
     * Refuses to truncate anything but the throwaway Testcontainers database.
     *
     * <p>Today this cannot fire: {@link TestcontainersPostgresInitializer} overrides
     * {@code tessary.jdbc-url} for every context, and this listener is registered only on the test
     * classpath. But that safety is positional, not structural — it holds because of where two
     * other files sit. Drop the initializer, let a test pin {@code tessary.jdbc-url} at a higher
     * precedence, or copy this class into a module wired against a real database, and the next
     * line would empty it. Asserting on the connection's own URL rather than on a property means
     * the check reads what is actually about to be truncated.
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
