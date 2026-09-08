// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Attaches the SDK to the Logback {@code OpenTelemetryAppender} (declared in
 * logback-spring.xml), in the {@code production} profile only.
 * <p>
 * Everything else is handled by Spring Boot 4's {@code spring-boot-starter-opentelemetry}:
 * the unified {@code OpenTelemetry} SDK, OTLP traces
 * ({@code OpenTelemetryTracingAutoConfiguration} + {@code OtlpTracingAutoConfiguration}),
 * OTLP logs ({@code OtlpLoggingAutoConfiguration}), and OTLP metrics via the
 * Micrometer {@code OtlpMeterRegistry}. The only gap the starter leaves is
 * installing the Logback appender: Logback parses its config before Spring beans
 * exist, so the appender buffers events until the SDK is attached here at
 * context refresh.
 */
@Configuration(proxyBeanMethods = false)
@Profile("production")
public class OtelExportConfig {

    @Bean
    OpenTelemetryAppenderInstaller openTelemetryAppenderInstaller(OpenTelemetry openTelemetry) {
        return new OpenTelemetryAppenderInstaller(openTelemetry);
    }

    static final class OpenTelemetryAppenderInstaller {
        OpenTelemetryAppenderInstaller(OpenTelemetry openTelemetry) {
            OpenTelemetryAppender.install(openTelemetry);
        }
    }
}
