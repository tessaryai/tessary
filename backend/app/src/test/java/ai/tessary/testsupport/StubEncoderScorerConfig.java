// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

import ai.tessary.classifier.detector.EncoderScorer;
import java.util.Locale;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A deterministic {@link EncoderScorer} for integration tests that exercise the encoder
 * built-ins without a running launcher. Text-keyed scores mirror the strong/weak keyword split: an
 * unambiguous escalation phrase scores above the frustration head's HIGH threshold, a merely
 * suggestive one lands in its LOW band, everything else stays quiet.
 *
 * <p>The scores are deliberately not adjacent to a band edge — they must keep those three meanings
 * when the head's operating point moves, as it has three times: 0.75/0.5 → 0.6/0.4 when frustration
 * repointed to cirimus, → 0.85/0.67 when the band was re-derived on human-labelled production
 * traffic, → 0.90/0.66 when {@code disappointment} left the proxy. A weak score sitting
 * exactly ON the new HIGH edge is what broke {@code ClassifierPrecisionModeIntegrationTest} the first
 * time; the second move stranded the weak score BELOW the new LOW edge, silencing it. Keep each score
 * mid-band, and re-check them here whenever the catalog's band changes — nothing else pins this file
 * to it.
 */
@TestConfiguration
public class StubEncoderScorerConfig {

    @Bean
    @Primary
    EncoderScorer stubEncoderScorer() {
        return (head, texts) ->
                texts.stream().map(StubEncoderScorerConfig::scoreOf).toList();
    }

    private static double scoreOf(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("you're not listening")) return 0.95; // > HIGH (0.90)
        if (t.contains("frustrating")) return 0.75; // mid LOW band [0.66, 0.90)
        return 0.02; // < LOW — also what the seeded preamble turn scores, so it never fires
    }
}
