// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link PromptBodyLimitInterceptor} over both prompt route families. */
@Configuration
public class PromptWebConfig implements WebMvcConfigurer {

    private final PromptBodyLimitInterceptor interceptor;

    public PromptWebConfig(PromptBodyLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/orgs/*/projects/*/prompts/**", "/v1/prompts/**");
    }
}
