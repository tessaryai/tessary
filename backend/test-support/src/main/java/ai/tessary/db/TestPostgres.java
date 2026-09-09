// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * JVM-wide singleton pgvector Postgres for the whole backend test suite.
 * One container per JVM (started on first class load, reaped
 * by the Testcontainers Ryuk container at JVM exit); each Spring context gets a freshly created
 * database via {@link #createIsolatedDatabase()} so cached contexts never share Liquibase or
 * tenant state.
 *
 * <p>The image is {@code pgvector/pgvector:pg16} — the same Postgres 16 image dev and prod run —
 * for full test==dev==prod parity, even though the schema no longer activates the {@code vector}
 * extension (the embedding substrate it backed was removed; the image is kept anyway
 * rather than fork the compose stack over one dropped extension). Docker must be available to
 * run the suite. Wired into every test context by {@link TestcontainersPostgresInitializer}.</p>
 */
public final class TestPostgres {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    @SuppressWarnings("resource") // JVM-lifetime singleton; reaped by the Testcontainers Ryuk container at exit
    private static final PostgreSQLContainer<?> CONTAINER = new PostgreSQLContainer<>(IMAGE);

    static {
        CONTAINER.start();
    }

    private TestPostgres() {}

    /** Create a fresh database in the singleton container and return its name. */
    public static String createIsolatedDatabase() {
        String dbName = "tessary_" + Long.toHexString(System.nanoTime());
        try (Connection c = DriverManager.getConnection(
                        CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
                Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE \"" + dbName + "\"");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to create isolated test database " + dbName, e);
        }
        return dbName;
    }

    /** JDBC URL for a database created via {@link #createIsolatedDatabase()}. */
    public static String jdbcUrl(String dbName) {
        return "jdbc:postgresql://"
                + CONTAINER.getHost()
                + ":"
                + CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/"
                + dbName;
    }

    public static String username() {
        return CONTAINER.getUsername();
    }

    public static String password() {
        return CONTAINER.getPassword();
    }
}
