// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.classifier.detector.EncoderScorer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * An {@link EncoderScorer} whose failure is switchable mid-test, for exercising the signal
 * sweep's fast-fail dead-letter path ({@code /classify} launcher transport failure)
 * without a running launcher. Throws while {@link Toggle#isThrowing()} is true (transport down);
 * flip it off to simulate the launcher recovering.
 */
@TestConfiguration
public class ThrowingEncoderScorerConfig {

    /** Mutable per-test switch, autowire this to flip the scorer between failing and healthy. */
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
    Toggle encoderScorerToggle() {
        return new Toggle();
    }

    @Bean
    @Primary
    EncoderScorer throwingEncoderScorer(Toggle toggle) {
        return (head, texts) -> {
            if (toggle.isThrowing()) {
                throw new IllegalStateException("launcher /classify transport failure (test)");
            }
            return texts.stream().map(t -> 0.02).toList();
        };
    }
}
