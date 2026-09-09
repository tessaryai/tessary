// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Liquibase against the {@link DataSourceConfig} bean. Spring Boot 4 removed the
 * auto-configured LiquibaseAutoConfiguration, so this declares the SpringLiquibase bean directly.
 */
@Configuration
public class LiquibaseConfig {

    /**
     * The bean name this configuration registers its {@link SpringLiquibase} under (the method name
     * below, {@code liquibase}). Kept as a named constant, rather than restated as a string, so
     * another bean can order itself after this one with {@code @DependsOn(BEAN_NAME)}; renaming the
     * bean method without updating this constant is then a compile error rather than a drifted
     * string that only breaks at boot.
     */
    public static final String BEAN_NAME = "liquibase";

    @Bean
    public SpringLiquibase liquibase(
            DataSource dataSource,
            @Value("${spring.liquibase.change-log:classpath:/db/changelog/db.changelog-master.yaml}") String changeLog,
            @Value("${spring.liquibase.enabled:true}") boolean enabled) {
        SpringLiquibase lb = new SpringLiquibase();
        lb.setDataSource(dataSource);
        lb.setChangeLog(changeLog);
        lb.setShouldRun(enabled);
        return lb;
    }
}
