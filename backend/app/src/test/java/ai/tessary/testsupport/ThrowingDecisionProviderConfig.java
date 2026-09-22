// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.decisions.DecisionProviderResolver;
import ai.tessary.llm.decisions.DecisionTarget;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A {@link DecisionProviderResolver} whose failure is switchable mid-test, for exercising the classifier
 * sweep's fast-fail dead-letter path through the Frustration detector without a provider. While {@link
 * Toggle#isThrowing()} is true the key lookup throws, as a database outage would; flip it off and every
 * project resolves a TypeSafe key.
 */
@TestConfiguration
public class ThrowingDecisionProviderConfig {

    /** Mutable per-test switch, autowire this to flip the resolver between failing and healthy. */
    public static final class Toggle {
        private final AtomicBoolean throwing = new AtomicBoolean(true);

        public void setThrowing(boolean value) {
            throwing.set(value);
        }

        boolean isThrowing() {
            return throwing.get();
        }
    }

    @Bean
    Toggle decisionProviderToggle() {
        return new Toggle();
    }

    @Bean
    @Primary
    DecisionProviderResolver throwingDecisionProviderResolver(Toggle toggle) {
        DecisionProviderResolver resolver = mock(DecisionProviderResolver.class);
        DecisionTarget target = new DecisionTarget(
                ModelProvider.TYPESAFE, "jev-latest", URI.create("https://api.typesafe.ai/v1/systemone"), "k");
        when(resolver.resolve(any(), any())).thenAnswer(invocation -> {
            if (toggle.isThrowing()) {
                throw new IllegalStateException("provider key lookup failure (test)");
            }
            return Optional.of(target);
        });
        return resolver;
    }
}
