// SPDX-License-Identifier: Apache-2.0
package ai.tessary.featureflags;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the default {@link FeatureFlags} adapter via a {@code @Bean} method guarded by {@link
 * ConditionalOnMissingBean} rather than a plain {@code @Service} on {@link DbFeatureFlags}, so a
 * build that registers its own {@code FeatureFlags} bean earlier can override this one instead of
 * colliding with it: two {@code @Service}-annotated beans would leave {@code
 * CapabilityService}'s constructor injection to fail with {@code NoUniqueBeanDefinitionException}.
 */
@Configuration(proxyBeanMethods = false)
public class FeatureFlagsConfig {

    /** The open adapter: per-org rows in {@code org_feature_flag}, no defaults of its own. */
    @Bean
    @ConditionalOnMissingBean(FeatureFlags.class)
    DbFeatureFlags dbFeatureFlags(OrgFeatureFlagRepository overrides) {
        return new DbFeatureFlags(overrides);
    }
}
