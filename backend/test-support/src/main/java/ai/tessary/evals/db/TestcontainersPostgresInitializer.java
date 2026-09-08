// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.db;

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
 * properties take precedence over {@code application.yaml}'s {@code ${EVALS_JDBC_URL:}} default
 * (and any ambient {@code EVALS_JDBC_URL}), so the suite is deterministic.</p>
 */
public class TestcontainersPostgresInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        String db = TestPostgres.createIsolatedDatabase();
        TestPropertyValues.of(
                        "evals.jdbc-url=" + TestPostgres.jdbcUrl(db),
                        "evals.db-username=" + TestPostgres.username(),
                        "evals.db-password=" + TestPostgres.password())
                .applyTo(applicationContext.getEnvironment());
    }
}
