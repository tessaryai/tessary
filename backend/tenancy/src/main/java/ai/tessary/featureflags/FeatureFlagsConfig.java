// SPDX-License-Identifier: Apache-2.0
package ai.tessary.featureflags;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A {@code @ConditionalOnMissingBean} factory rather than {@code @Service} on {@link DbFeatureFlags}, so another
 * build's {@code FeatureFlags} bean overrides this instead of colliding.
 */
@Configuration(proxyBeanMethods = false)
public class FeatureFlagsConfig {

    @Bean
    @ConditionalOnMissingBean(FeatureFlags.class)
    DbFeatureFlags dbFeatureFlags(OrgFeatureFlagRepository overrides) {
        return new DbFeatureFlags(overrides);
    }
}
