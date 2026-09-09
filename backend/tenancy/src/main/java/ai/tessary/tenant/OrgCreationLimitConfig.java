// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default org-creation limit for this build: a {@code @Bean} method, not a component-scanned
 * class, so {@link ConditionalOnMissingBean} sees only other candidates and a same-typed bean
 * elsewhere on the classpath can override it cleanly.
 */
@Configuration(proxyBeanMethods = false)
public class OrgCreationLimitConfig {

    /** The default: one org per install, bootstrapped on first signup. */
    @Bean
    @ConditionalOnMissingBean(OrgCreationLimit.class)
    OrgCreationLimit orgCreationLimit() {
        return () -> 1;
    }
}
