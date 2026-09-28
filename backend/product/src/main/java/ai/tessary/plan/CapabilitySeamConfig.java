// SPDX-License-Identifier: Apache-2.0
package ai.tessary.plan;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires this build's {@link CapabilityDefaults}; another build's bean takes precedence. */
@Configuration(proxyBeanMethods = false)
public class CapabilitySeamConfig {

    @Bean
    @ConditionalOnMissingBean(CapabilityDefaults.class)
    CapabilityDefaults capabilityDefaults() {
        return CapabilityDefaults.open();
    }
}
