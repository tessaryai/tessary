// SPDX-License-Identifier: Apache-2.0
package ai.tessary.db;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares SpringLiquibase directly, since Spring Boot 4 no longer auto-configures it. */
@Configuration
public class LiquibaseConfig {

    /** For {@code @DependsOn(BEAN_NAME)}; must match the bean method name. */
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
