// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Defaults every Spring test context to unauthenticated, since most of this module's
 * {@code @SpringBootTest} classes never think about auth.
 *
 * <p>Registered globally in {@code src/test/resources/META-INF/spring.factories} beside
 * {@code TestcontainersPostgresInitializer}, so no test needs per-class wiring.
 *
 * <p>Only sets {@code tessary.auth.disabled} when the property is not already present, so a
 * test's own {@code @DynamicPropertySource} override to enforce real auth is not silently
 * clobbered. Do not use a fake external-provider credential as an indirect toggle for auth
 * enforcement; override {@code tessary.auth.disabled=false} directly instead, and say what you mean.
 */
public class TestAuthDisabledInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        // TestPropertyValues.applyTo(env) inserts at the front of the property source list, the
        // highest precedence, so this default reaches every test that never sets auth explicitly.
        // The guard matters because this initializer runs after dynamic-property registration:
        // without it, the same front-of-list insertion would silently override a test's own
        // tessary.auth.disabled=false and disable auth for that test instead of enforcing it.
        if (!applicationContext.getEnvironment().containsProperty("tessary.auth.disabled")) {
            TestPropertyValues.of("tessary.auth.disabled=true").applyTo(applicationContext.getEnvironment());
        }
    }
}
