// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import ai.tessary.config.TessaryProperties;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Postgres {@link DataSource}. Builds a HikariCP pool against {@code TESSARY_JDBC_URL} (with
 * {@code TESSARY_DB_USERNAME}/{@code TESSARY_DB_PASSWORD}). This is the single path for every
 * environment:
 *
 * <ul>
 *   <li><b>Prod / dev</b> — Postgres runs as the {@code pgvector/pgvector:pg16} container in
 *       {@code docker-compose.yml} / {@code docker-compose.dev.yml}, which sets
 *       {@code TESSARY_JDBC_URL}.</li>
 *   <li><b>Tests</b> — a JVM-singleton {@code pgvector/pgvector:pg16} Testcontainers instance
 *       backs the suite; {@code TestcontainersPostgresInitializer} (registered globally via
 *       {@code src/test/resources/META-INF/spring.factories}) injects {@code TESSARY_JDBC_URL}
 *       into every test context, pointed at a freshly created database.</li>
 * </ul>
 *
 * <p>There is no embedded fallback: a blank {@code TESSARY_JDBC_URL} is a configuration error and
 * fails fast.</p>
 *
 * <p><b>The pool binds {@code spring.datasource.hikari.*}, which until now it silently ignored.</b>
 * Building the {@code DataSource} here replaces Boot's auto-configured one, so the whole
 * {@code spring.datasource.hikari} block in {@code application-production.yaml} was inert: an operator
 * raising {@code maximum-pool-size} to relieve pool pressure got no effect at all. The values set below
 * are defaults and the yaml overrides them.
 *
 * <p>The key that had actually drifted is {@code connection-timeout}. The yaml asks for 10s; the code
 * was using Hikari's 30s default, and a measured overload showed exactly that — a 34s p99 and a 55s
 * worst case where the documented behaviour was a 10s failure. Under Loom the pool, not the thread
 * count, is the write path's ceiling, so how long a caller waits for a connection is the difference
 * between backpressure and a stall.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(TessaryProperties props) {
        String jdbcUrl = props.getJdbcUrl();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("TESSARY_JDBC_URL is required. In dev/prod it is set by docker-compose; "
                    + "in tests it is injected from the Testcontainers pgvector container.");
        }

        // The no-argument constructor, not HikariDataSource(HikariConfig): that form seals its
        // configuration immediately, and @ConfigurationProperties binds after the factory method returns,
        // so binding into a sealed pool would throw. These are the defaults; the yaml overrides them.
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(1);
        ds.setLeakDetectionThreshold(30_000);
        ds.setPoolName("tessary-hikari");
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(props.getDbUsername());
        ds.setPassword(props.getDbPassword());
        log.info("postgres datasource at {}", jdbcUrl);
        return ds;
    }
}
