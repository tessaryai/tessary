// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Gives every Spring test context its own database in the singleton pgvector container {@link TestPostgres}, registered
 * via {@code spring.factories}. Overrides {@code TESSARY_JDBC_URL} so the suite is deterministic.
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
