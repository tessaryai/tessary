// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingDetailView;
import ai.tessary.classifier.finding.BehaviorDtos.BehaviorFindingView;
import ai.tessary.classifier.toolerror.ToolErrorEvidence.RateDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
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

        BehaviorFindingDetailView view = BehaviorFindingDetailView.of(row, null, null, null, null, null, null, null);

        assertEquals(
                "summarize", java.util.Objects.requireNonNull(view.metric()).bucketKey());
        assertEquals(2.4, view.metric().ratio());
        assertNull(view.toolError());
    }

    /**
     * The owner's detail reaches the wire as {@code rate}, the six tuning fields and {@code words} at the top level,
     * with a null field present as JSON null. The bugs are a renamed or nested key the generated frontend types do not
     * read, and a dropped null the page cannot tell from a missing field.
     */
    @Test
    void aClassifierDetailSerialisesItsRateSixFieldsAndWords() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ClassifierDetail detail = new ClassifierDetail(
                new RateDetail(
                        "cs-1", 0.02, 0.09, 7.0, 400, 120, 11, List.of(), false, List.of(), null, null, null, "up", 6.2,
                        4.0, 0.3, 0.5),
                8L,
                "flagged",
                0.7,
                500L,
                4.0,
                null,
                new ClassifierDetail.Words("reply", "replies", "since onset", "learned rate", null, "above 0.7"));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(detail));

        List<String> keys = new ArrayList<>();
        json.fieldNames().forEachRemaining(keys::add);
        assertEquals(
                List.of(
                        "rate",
                        "baselineFlagged",
                        "flagLabel",
                        "flagThreshold",
                        "arlTarget",
                        "minDecisionInterval",
                        "learningUntil",
                        "words"),
                keys);
        assertEquals("cs-1", json.get("rate").get("bucketKey").asText());
        ((ObjectNode) json).remove("rate");
        assertEquals(
                mapper.readTree("{\"baselineFlagged\":8,\"flagLabel\":\"flagged\",\"flagThreshold\":0.7,"
                        + "\"arlTarget\":500,\"minDecisionInterval\":4.0,\"learningUntil\":null,"
                        + "\"words\":{\"unitOne\":\"reply\",\"unitMany\":\"replies\",\"figureNote\":\"since onset\","
                        + "\"referenceLabel\":\"learned rate\",\"referenceDetail\":null,"
                        + "\"thresholdLine\":\"above 0.7\"}}"),
                json);
    }
}
