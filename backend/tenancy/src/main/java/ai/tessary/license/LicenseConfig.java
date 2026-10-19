// SPDX-License-Identifier: Apache-2.0
package ai.tessary.license;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Another build's {@link LicenseSource} bean takes precedence; this build has no license. */
@Configuration(proxyBeanMethods = false)
public class LicenseConfig {

    @Bean
    @ConditionalOnMissingBean(LicenseSource.class)
    LicenseSource noLicense() {
        return LicenseSource.none();
    }
}
