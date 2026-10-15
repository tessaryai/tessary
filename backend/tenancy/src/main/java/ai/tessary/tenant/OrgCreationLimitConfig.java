// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A {@code @Bean} method rather than a scanned class, so {@link ConditionalOnMissingBean} sees only other candidates
 * and another bean can override it.
 */
@Configuration(proxyBeanMethods = false)
public class OrgCreationLimitConfig {

    @Bean
    @ConditionalOnMissingBean(OrgCreationLimit.class)
    OrgCreationLimit orgCreationLimit() {
        return userId -> 1;
    }
}
