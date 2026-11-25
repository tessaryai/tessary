// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.job;

import ai.tessary.config.EndUsersProperties;
import ai.tessary.retention.RetentionResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers this build's history horizon; another build's bean takes precedence. */
@Configuration(proxyBeanMethods = false)
public class EndUsersSeamConfig {

    @Bean
    @ConditionalOnMissingBean(EndUserHistoryHorizon.class)
    EndUserHistoryHorizon endUserHistoryHorizon(RetentionResolver retention, EndUsersProperties props) {
        return new TraceTtlHistoryHorizon(retention, props);
    }
}
