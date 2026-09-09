// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import ai.tessary.config.TessaryProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    public DataSource dataSource(TessaryProperties props) {
        String jdbcUrl = props.getJdbcUrl();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("TESSARY_JDBC_URL is required. In dev/prod it is set by docker-compose; "
                    + "in tests it is injected from the Testcontainers pgvector container.");
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.postgresql.Driver");
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(1);
        cfg.setLeakDetectionThreshold(30_000);
        cfg.setPoolName("evals-hikari");
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(props.getDbUsername());
        cfg.setPassword(props.getDbPassword());
        log.info("postgres datasource at {}", jdbcUrl);
        return new HikariDataSource(cfg);
    }
}
