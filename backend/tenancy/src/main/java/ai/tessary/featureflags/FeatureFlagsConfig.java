// SPDX-License-Identifier: Apache-2.0
package ai.tessary.featureflags;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the OPEN {@link FeatureFlags} adapter — and does it in a way a paid adapter can displace.
 *
 * <p><b>Why a {@code @Bean} method with {@link ConditionalOnMissingBean} rather than a plain {@code @Service}
 * on {@link DbFeatureFlags}.</b> {@code TessaryApplication} is a bare {@code @SpringBootApplication} on
 * {@code ai.tessary} with no {@code scanBasePackages}, so component scan sweeps every jar on the
 * classpath. Annotate the open implementation {@code @Service} and the moment a paid adapter is also on the
 * classpath the context has two {@code FeatureFlags} beans and {@code CapabilityService}'s single-argument
 * constructor injection dies with {@code NoUniqueBeanDefinitionException}. This shape means the paid adapter
 * simply wins by existing.
 *
 * <p><b>The ordering question this used to leave open is now answered.</b> A {@code @ConditionalOnMissingBean}
 * in a plain {@code @Configuration} is evaluated in registration order, so whether the paid adapter is
 * registered in time depends entirely on HOW it arrives. It arrives as a Spring Boot auto-configuration in the
 * paid jar that {@code @ComponentScan}s its own package (#881; the class is {@code PaidPlanAutoConfiguration},
 * named without its package on purpose — {@code scripts/check-open-boundary.sh} greps open source for paid
 * package prefixes and does not care that this one is inside a javadoc comment), and that shape wins: a component scan registers its definitions during the parse phase, which completes before
 * any {@code @Bean} method's condition is read. A paid {@code @Bean} METHOD on that auto-configuration would
 * NOT — auto-configuration is processed after user configuration, and this class is user configuration.
 * {@code PaidPlanAutoConfigurationTest} runs both editions and asserts exactly one {@code FeatureFlags} bean in
 * each. Note the converse is unavailable: turning this class into an {@code @AutoConfiguration} does nothing,
 * because it sits inside {@code TessaryApplication}'s scan root, so Spring keeps the component-scanned
 * registration and silently discards the imported one along with its ordering annotations.
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
