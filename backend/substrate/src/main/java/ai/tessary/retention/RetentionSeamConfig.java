// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fixes nothing; another build's bean takes precedence. */
@Configuration(proxyBeanMethods = false)
public class RetentionSeamConfig {

    @Bean
    @ConditionalOnMissingBean(FixedRetention.class)
    FixedRetention fixedRetention() {
        return FixedRetention.none();
    }
}
