// SPDX-License-Identifier: Apache-2.0
package ai.tessary.edition;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires this build's default {@link Edition} bean. The {@code @ConditionalOnMissingBean} sits on the
 * {@code @Bean} method rather than the class, so a bean registered elsewhere during the parse phase
 * can override it.
 */
@Configuration(proxyBeanMethods = false)
public class EditionConfig {

    @Bean
    @ConditionalOnMissingBean(Edition.class)
    Edition edition() {
        return Edition.open();
    }
}
