// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * JVM-wide singleton pgvector Postgres for the whole backend test suite. One container per JVM, started on first class
 * load; each Spring context gets its own database so cached contexts never share tenant state.
 *
 * <p>The image is {@code pgvector/pgvector:pg16}, the Postgres dev and prod run. Durability is switched off and the
 * data directory is a tmpfs: the container is thrown away at JVM exit, so nothing written needs to survive a crash.
 */
public final class TestPostgres {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @SuppressWarnings("resource") // JVM-lifetime singleton, removed with the JVM
    private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>(IMAGE)
            .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off")
            .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"));

    /** Keyed by changelog: a migrated template is only a valid copy source for the changelog that built it. */
    private static final Map<String, String> TEMPLATES = new ConcurrentHashMap<>();

    static {
        CONTAINER.start();
    }

    private TestPostgres() {}

    /** Create an empty database in the singleton container and return its name. */
    public static String createIsolatedDatabase() {
        return createDatabase("");
    }

    /**
     * Create a database already migrated by {@code changeLog} and return its name. Liquibase runs once per JVM into a
     * template; every database is a {@code CREATE DATABASE ... TEMPLATE} copy of it, so a context's own Liquibase run
     * finds nothing to apply. The template takes no connections after migrating, which a copy requires.
     */
    public static String createMigratedDatabase(String changeLog) {
        String template = TEMPLATES.computeIfAbsent(changeLog, TestPostgres::migrateTemplate);
        return createDatabase(" TEMPLATE \"" + template + "\"");
    }

    private static String migrateTemplate(String changeLog) {
        String template = createIsolatedDatabase();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(jdbcUrl(template));
        dataSource.setUser(username());
        dataSource.setPassword(password());
        // SpringLiquibase, as the app's LiquibaseConfig uses, so the template records the same changelog path the
        // context's own run looks for; a different runner may record another spelling and re-apply everything.
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changeLog);
        liquibase.setResourceLoader(new DefaultResourceLoader());
        try {
            liquibase.afterPropertiesSet();
        } catch (LiquibaseException e) {
            throw new IllegalStateException("failed to migrate template database " + template, e);
        }
        return template;
    }

    private static String createDatabase(String clause) {
        String dbName = "tessary_" + Long.toHexString(System.nanoTime());
        try (Connection c = DriverManager.getConnection(
                        CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
                Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE \"" + dbName + "\"" + clause);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to create test database " + dbName, e);
        }
        return dbName;
    }

    /** JDBC URL for a database this class created. */
    public static String jdbcUrl(String dbName) {
        return urlPrefix() + dbName;
    }

    /**
     * Everything in a {@link #jdbcUrl(String)} up to and including the final slash, so a caller about to do something
     * destructive can prove the connection it holds points at this throwaway container. The mapped port is assigned
     * by Docker per run, which is what makes this worth checking rather than assuming.
     */
    public static String urlPrefix() {
        return "jdbc:postgresql://"
                + CONTAINER.getHost()
                + ":"
                + CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/";
    }

    public static String username() {
        return CONTAINER.getUsername();
    }

    public static String password() {
        return CONTAINER.getPassword();
    }
}
