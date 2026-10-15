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
 * The HikariCP pool for every environment, from {@code TESSARY_JDBC_URL}; blank fails fast. Building it here replaces
 * Boot's, so it binds {@code spring.datasource.hikari.*} itself; the values below are defaults the yaml overrides.
 * Under Loom the pool is the write path's ceiling, so {@code connection-timeout} decides backpressure versus stall.
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

        // Not HikariDataSource(HikariConfig): that seals the config, and @ConfigurationProperties binds after this
        // returns.
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(1);
        ds.setLeakDetectionThreshold(30_000);
        // A Micrometer pool= tag; renaming it splits the series (frozen per telemetry-naming.md).
        ds.setPoolName("evals-hikari");
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(props.getDbUsername());
        ds.setPassword(props.getDbPassword());
        // spring.datasource.hikari.jdbc-url would win if set, so this line is not the authority.
        log.info("postgres datasource at {}", jdbcUrl);
        return ds;
    }
}
