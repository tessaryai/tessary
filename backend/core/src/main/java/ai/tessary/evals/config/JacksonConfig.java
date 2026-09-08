// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Centralised, Spring-managed {@link ObjectMapper} beans, replacing the per-class
 * {@code new ObjectMapper()} instantiations that were scattered across the stack.
 *
 * <p><b>Strictness is preserved verbatim.</b> Every call site this replaced used a
 * bare {@code new ObjectMapper()}, whose defaults include
 * {@code FAIL_ON_UNKNOWN_PROPERTIES = true}. Spring Boot's own auto-configured
 * {@code ObjectMapper} <em>disables</em> that feature, so adopting it would have
 * silently relaxed deserialization across the codebase. To keep behaviour identical
 * we publish our own {@link Primary} bean built from {@code new ObjectMapper()} —
 * default (strict) Jackson configuration, shared as one thread-safe singleton.
 *
 * <p>{@code @Primary} ensures this strict mapper wins constructor injection over the
 * auto-configured one. Call sites that need different behaviour (e.g. lenient YAML
 * bundle import) derive from the relevant bean explicitly via {@code copy()} rather
 * than mutating a shared instance.
 */
@Configuration
public class JacksonConfig {

    /**
     * The default JSON mapper, configured exactly like {@code new ObjectMapper()}
     * (strict: unknown properties fail). Shared singleton; never mutated in place.
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * YAML-backed mapper for reading/writing {@code .tessary/} pipeline shards and
     * prompt-craft manifests. Mirrors the previous plain
     * {@code new ObjectMapper(new YAMLFactory())} with module discovery. Consumers
     * that need extra deserialization leniency (e.g. ignoring unknown bundle fields)
     * derive a configured {@code copy()} rather than sharing config.
     */
    @Bean("yamlObjectMapper")
    public ObjectMapper yamlObjectMapper() {
        return new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
    }
}
