// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Supplies every Spring test context with the AES-GCM key {@code SecretBox} needs, since a
 * {@code @SpringBootTest} that seals or opens an ingestion-source credential fails without one and
 * no test cares what the bytes are.
 *
 * <p>Registered globally in {@code src/test/resources/META-INF/spring.factories} beside
 * {@code TestcontainersPostgresInitializer} and {@code TestAuthDisabledInitializer}, so no test
 * needs per-class wiring.
 *
 * <p>This replaces 80 byte-identical {@code @DynamicPropertySource} copies, and the reason is
 * throughput rather than tidiness: {@code DynamicPropertiesContextCustomizer} equality compares the
 * {@code Set<Method>} it was built from, so 80 declaring classes meant 80 unequal customizers, 80
 * distinct context cache keys, and 80 Spring boots of the same application.
 *
 * <p>Only sets {@code tessary.secret-key} when the property is not already present, so a test that
 * needs a different key (a rotation or a decrypt-failure case) can still state its own.
 */
public class TestSecretKeyInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        // application.yaml carries `secret-key: ${TESSARY_SECRET_KEY:}`, so the key is always
        // *present* and always blank unless something supplies it; containsProperty would therefore
        // never be false. Check for a non-blank value instead, which is what a test overriding this
        // would actually set.
        String existing = applicationContext.getEnvironment().getProperty("tessary.secret-key");
        if (existing == null || existing.isBlank()) {
            TestPropertyValues.of("tessary.secret-key=" + TEST_KEY).applyTo(applicationContext.getEnvironment());
        }
    }
}
