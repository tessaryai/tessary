// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.substrate.v2;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Defaults every Spring test context to no span resolvers, so {@link PathResolver} and
 * {@link CorrelationBackfiller} do not tick against whatever the running class seeded.
 *
 * <p>In the shared context they raced the schema clean between classes: a {@code markOrphanPaths}
 * pass over a large seed held its locks for over 16 minutes and failed every later class. No test
 * needs them ticking, since the fixtures write {@code path} and correlation directly, and a test that
 * exercises a resolver constructs it and calls {@code runOnce()}.
 *
 * <p>Only sets the property when it is not already present, the same guard as
 * {@code TestAuthDisabledInitializer}, so a test can still opt back in with
 * {@code tessary.ingest.substrate.resolvers-enabled=true}.
 */
public class TestResolversDisabledInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final String RESOLVERS_ENABLED = "tessary.ingest.substrate.resolvers-enabled";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        if (!applicationContext.getEnvironment().containsProperty(RESOLVERS_ENABLED)) {
            TestPropertyValues.of(RESOLVERS_ENABLED + "=false").applyTo(applicationContext.getEnvironment());
        }
    }
}
