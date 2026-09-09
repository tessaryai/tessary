// SPDX-License-Identifier: Apache-2.0
package ai.tessary.edition;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the OPEN edition's {@link Edition}, in the one shape that lets the paid overlay displace it. Same
 * trap and same fix as {@code OrgCreationLimitConfig} and {@code FeatureFlagsConfig}: the condition has to
 * sit on a {@code @Bean} METHOD so the overlay's component-scanned {@code PaidEdition}, registered during
 * the parse phase, is already present when this condition is evaluated. See those two classes for the
 * commit that shipped the component-scanned variant failing in both editions.
 */
@Configuration(proxyBeanMethods = false)
public class EditionConfig {

    @Bean
    @ConditionalOnMissingBean(Edition.class)
    Edition edition() {
        return Edition.open();
    }
}
