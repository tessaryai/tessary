// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.catalog.BuiltInDetector;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The sentence a finding and its case are both called by, for the causes that have one of their own. */
class FindingTitleTest {

    @Test
    void aGroundednessRateFindingWithNoCallSiteColumnNamesTheNativeKey() {
        String payload = "{\"cause_kind\":\"groundedness_rate\",\"native_cause_key\":\"summarize\"}";

        assertEquals("Answers on summarize became less grounded", FindingTitle.of(finding(null, payload)));
    }

    /**
     * A frustration finding whose payload carries no rates keeps its sentence and names the call site; a
     * title that printed "0.0% to 0.0%" would state a move nobody measured.
     */
    @Test
    void aFrustrationFindingWithoutRatesNamesOnlyItsCallSite() {
        FindingRow row = FindingRowBuilder.of(BuiltInDetector.Kind.FRUSTRATION)
                .payload("{\"cause_kind\":\"frustration_rate\",\"native_cause_key\":\"checkout-agent\","
                        + "\"baseline_rate\":0.2}")
                .build();

        assertEquals("Frustrated sessions increased on checkout-agent", FindingTitle.of(row));
    }

    /**
     * A cause the open code has no sentence for reads the title its writer stored. The bug is a finding whose writer
     * named it shown under its bare cause key.
     */
    @Test
    void aCauseWithNoSentenceReadsItsStoredTitle() {
        FindingRow row = FindingRowBuilder.of("owned-x")
                .title("Flagged replies rose from 2.0% to 9.0%")
                .payload("{\"cause_kind\":\"owned_rate\",\"native_cause_key\":\"checkout-agent:up\"}")
                .build();

        assertEquals("Flagged replies rose from 2.0% to 9.0%", FindingTitle.of(row));
    }

    /** With no stored title, or a blank one, such a cause still reads its key rather than an empty headline. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  "})
    void aCauseWithNoSentenceFallsBackToTheCauseKeyWhenNoTitleIsStored(@Nullable String title) {
        FindingRow row = FindingRowBuilder.of("owned-x")
                .title(title)
                .payload("{\"cause_kind\":\"owned_rate\",\"native_cause_key\":\"checkout-agent:up\"}")
                .build();

        assertEquals("checkout-agent:up", FindingTitle.of(row));
    }

    private static FindingRow finding(@Nullable String callSiteId, String payloadJson) {
        return FindingRowBuilder.of(BuiltInDetector.Kind.GROUNDEDNESS)
                .causeKey("sig-1:" + (callSiteId == null ? "summarize" : callSiteId))
                .subjectLabel("Groundedness")
                .callSiteId(callSiteId)
                .payload(payloadJson)
                .build();
    }
}
