// SPDX-License-Identifier: Apache-2.0
package ai.tessary.edition;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class EditionConfig {

    @Bean
    Edition edition() {
        return Edition.open();
    }
}
