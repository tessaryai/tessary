// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring-managed {@link ObjectMapper} beans. The {@link Primary} JSON mapper is a plain {@code new ObjectMapper()} so
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} stays on; Boot's auto-configured mapper disables it. Lenient callers derive a
 * {@code copy()}.
 */
@Configuration
public class JacksonConfig {

    /** Strict: unknown properties fail. Never mutated in place. */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /** For {@code .tessary/} shards and prompt-craft manifests. Lenient callers derive a {@code copy()}. */
    @Bean("yamlObjectMapper")
    public ObjectMapper yamlObjectMapper() {
        return new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    }
}
