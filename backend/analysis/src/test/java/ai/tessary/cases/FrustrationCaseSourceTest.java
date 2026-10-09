// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.FindingRowBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A case's onset is stamped once, at open, from the finding's own {@code onset_at} text column. An onset the
 * parser cannot read must leave the case unbracketed; throwing there fails the whole case open, and a
 * confirmed regression never reaches Triage.
 */
class FrustrationCaseSourceTest {

    private final FrustrationCaseSource source = new FrustrationCaseSource();

    @ParameterizedTest
    @ValueSource(strings = {"", "2026-09-01 00:00:00+00"})
    void anOnsetThatIsNotAnInstantOpensTheCaseUnbracketed(String onsetAt) {
        var finding = FindingRowBuilder.of(BuiltInDetector.Kind.FRUSTRATION)
                .callSiteId("checkout-agent")
                .onsetAt(onsetAt)
                .build();

        assertNull(source.shape(finding).onsetAt());
    }
}
