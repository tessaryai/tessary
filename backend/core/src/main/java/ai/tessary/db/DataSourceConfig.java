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
 * <p>Under Loom the pool, not the thread count, is the write path's ceiling, so {@code
 * connection-timeout} decides whether a saturated pool answers as backpressure or as a stall. It is the
 * key this binding actually changes: the yaml asks for 10s and the pool had been using Hikari's 30s
 * default.
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
        // evals-hikari, not tessary-hikari: it is a Micrometer pool= tag, so renaming it splits the series.
        // telemetry-naming.md records it as deliberately frozen through the namespace rename.
        ds.setPoolName("evals-hikari");
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(props.getDbUsername());
        ds.setPassword(props.getDbPassword());
        // The configured URL. spring.datasource.hikari.jdbc-url binds after this returns and would win,
        // which no deployment sets — but this line is not the authority on what the pool connects to.
        log.info("postgres datasource at {}", jdbcUrl);
        return ds;
    }
}
