// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The stored triage citations read back onto a finding row. The bugs are a script's receipt dropped on the
 * way out, and a blob of the wrong shape failing the whole findings list instead of showing no citations.
 */
class BehaviorDtosTest {

    @Test
    void storedCitationsReadBackAndAWrongShapeReadsAsNone() {
        assertEquals(
                List.of(new BehaviorTriageVerdict.Citation("checks/by_model.py", "split by model", "haiku 41%")),
                BehaviorFindingView.citations(
                        "[{\"path\":\"checks/by_model.py\",\"reason\":\"split by model\",\"stdout\":\"haiku 41%\","
                                + "\"recomputed\":true}]"));
        assertEquals(List.of(), BehaviorFindingView.citations("{\"path\":\"a\"}"));
    }

    /**
     * A drift finding's page reads its evidence as a distribution shift and nothing else; handing it to the
     * rate reader instead would render an empty chart for a finding that has numbers.
     */
    @Test
    void aDriftFindingsPageCarriesItsShiftAndNoRate() {
        FindingRow row = FindingRowBuilder.of(BuiltInDetector.Kind.DURATION_DRIFT)
                .payload("{\"cause_kind\":\"distribution_shift\",\"measure\":\"turn_duration\","
                        + "\"bucket\":{\"kind\":\"call_site\",\"key\":\"summarize\"},\"ratio\":2.4}")
                .build();

        BehaviorFindingDetailView view = BehaviorFindingDetailView.of(row, null, null, null, null, null);

        assertEquals(
                "summarize", java.util.Objects.requireNonNull(view.metric()).bucketKey());
        assertEquals(2.4, view.metric().ratio());
        assertNull(view.toolError());
    }
}
