// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.plan;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link CapabilityInterceptor} over the API surface. */
@Configuration
public class CapabilityWebConfig implements WebMvcConfigurer {

    private final CapabilityInterceptor interceptor;

    public CapabilityWebConfig(CapabilityInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
