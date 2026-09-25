// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.catalog.BuiltInDetector;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The sentence a finding and its case are both called by, for the causes that have one of their own. */
class FindingTitleTest {

    /**
     * A groundedness rate finding says which way its call site moved and nothing about how far: the flagged
     * rate counts the model's false alarms, so the direction is reliable where the size is not.
     */
    @Test
    void aGroundednessRateFindingNamesItsCallSiteAndNoNumbers() {
        String payload = "{\"cause_kind\":\"groundedness_rate\",\"native_cause_key\":\"rag-answer\","
                + "\"baseline_rate\":0.021,\"current_rate\":0.064}";

        assertEquals("Answers on rag-answer became less grounded", FindingTitle.of(finding("rag-answer", payload)));
    }

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

    private static FindingRow finding(@Nullable String callSiteId, String payloadJson) {
        return new FindingRow(
                "fnd-1",
                "proj-1",
                BuiltInDetector.Kind.GROUNDEDNESS,
                "sig-1:" + (callSiteId == null ? "summarize" : callSiteId),
                FindingRow.SubjectKind.CLASSIFIER,
                "sig-1",
                "Groundedness",
                callSiteId,
                FindingRow.Status.OPEN,
                "2026-09-01T00:00:00Z",
                "2026-09-02T00:00:00Z",
                null,
                null,
                null,
                12,
                payloadJson,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "2026-09-01T00:00:00Z",
                "2026-09-02T00:00:00Z");
    }
}
