// SPDX-License-Identifier: Apache-2.0
package ai.tessary.telemetry;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Defaults every Spring test context to no telemetry, since a {@code @SpringBootTest} otherwise
 * boots {@link TelemetryHeartbeat} for real: its first tick fires 5-30s after context startup
 * (comfortably inside most integration tests' runtime), each test's throwaway Testcontainers
 * Postgres mints its own {@code instance_id}, and the ping goes out to the real
 * {@code home.tessary.ai} as a one-off instance reporting {@code app_version=dev}.
 *
 * <p>Registered globally in {@code src/test/resources/META-INF/spring.factories} beside {@link
 * ai.tessary.auth.TestAuthDisabledInitializer}, so no test needs per-class wiring.
 *
 * <p>Only sets {@code tessary.telemetry.enabled} when the property is not already present, so a
 * test that deliberately wants the heartbeat running can still opt in via its own {@code
 * @DynamicPropertySource} without this initializer clobbering it — same guard, same reasoning as
 * the auth initializer.
 */
public class TestTelemetryDisabledInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        if (!applicationContext.getEnvironment().containsProperty("tessary.telemetry.enabled")) {
            TestPropertyValues.of("tessary.telemetry.enabled=false")
                    .applyTo(applicationContext.getEnvironment());
        }
    }
}
