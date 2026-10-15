// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Points every Spring test context at its own database in the JVM-singleton {@link TestPostgres}, already migrated
 * by the context's changelog. Registered globally via {@code src/test/resources/META-INF/spring.factories}.
 *
 * <p>The injected properties take precedence over {@code application.yaml}'s {@code ${TESSARY_JDBC_URL:}} default
 * (and any ambient {@code TESSARY_JDBC_URL}), so the suite is deterministic.
 */
public class TestcontainersPostgresInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        String changeLog = applicationContext.getEnvironment().getRequiredProperty("spring.liquibase.change-log");
        String db = TestPostgres.createMigratedDatabase(changeLog);
        TestPropertyValues.of(
                        "tessary.jdbc-url=" + TestPostgres.jdbcUrl(db),
                        "tessary.db-username=" + TestPostgres.username(),
                        "tessary.db-password=" + TestPostgres.password())
                .applyTo(applicationContext.getEnvironment());
    }
}
