// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * JVM-wide singleton Postgres for the backend tests; each Spring context gets a fresh database via
 * {@link #createIsolatedDatabase()}. The {@code pgvector/pgvector:pg16} image matches dev and prod. Wired in by
 * {@link TestcontainersPostgresInitializer}.
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

    public static String jdbcUrl(String dbName) {
        return urlPrefix() + dbName;
    }

    /**
     * Lets a destructive caller prove its connection points at this throwaway container; the mapped port changes per
     * run.
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
