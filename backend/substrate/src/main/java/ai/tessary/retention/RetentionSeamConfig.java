// SPDX-License-Identifier: Apache-2.0
package ai.tessary.retention;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires this build's {@link FixedRetention}, which fixes nothing; another build's bean takes precedence. */
@Configuration(proxyBeanMethods = false)
public class RetentionSeamConfig {

    @Bean
    @ConditionalOnMissingBean(FixedRetention.class)
    FixedRetention fixedRetention() {
        return FixedRetention.none();
    }
}
