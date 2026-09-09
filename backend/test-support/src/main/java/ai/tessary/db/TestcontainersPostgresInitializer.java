// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Points every Spring test context at a freshly created database in the JVM-singleton pgvector
 * container {@link TestPostgres}. Registered globally via
 * {@code src/test/resources/META-INF/spring.factories}, so all {@code @SpringBootTest} classes
 * pick it up with no per-test wiring.
 *
 * <p>Each context gets its own database (so cached contexts stay isolated) and Liquibase runs
 * the full changelog against real pgvector Postgres, exactly like dev/prod. The injected
 * properties take precedence over {@code application.yaml}'s {@code ${TESSARY_JDBC_URL:}} default
 * (and any ambient {@code TESSARY_JDBC_URL}), so the suite is deterministic.</p>
 */
public class TestcontainersPostgresInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        String db = TestPostgres.createIsolatedDatabase();
        TestPropertyValues.of(
                        "tessary.jdbc-url=" + TestPostgres.jdbcUrl(db),
                        "tessary.db-username=" + TestPostgres.username(),
                        "tessary.db-password=" + TestPostgres.password())
                .applyTo(applicationContext.getEnvironment());
    }
}
