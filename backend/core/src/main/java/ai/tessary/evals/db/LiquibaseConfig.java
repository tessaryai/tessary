// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.db;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Liquibase against the {@link DataSourceConfig} bean. Spring Boot 4
 * removed the auto-configured LiquibaseAutoConfiguration that earlier versions
 * shipped, so we declare the SpringLiquibase bean ourselves.
 */
@Configuration
public class LiquibaseConfig {

    /**
     * The bean name this configuration registers its {@link SpringLiquibase} under — the method
     * name below, {@code liquibase}. Referenced by name (rather than restated as a string) by the
     * paid {@code PaidDbAutoConfiguration}'s {@code @DependsOn}, so the open Liquibase bean must run
     * — and finish applying its master changelog — before the paid one starts. Renaming the bean
     * method without updating this constant is a compile error on the paid side, not a drifted
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
