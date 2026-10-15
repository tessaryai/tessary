// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Attaches the SDK to Logback's {@code OpenTelemetryAppender} in production; the starter covers everything else.
 * Logback parses its config before beans exist, so the appender buffers until this runs at context refresh.
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
