// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.open.errors.RcaError;
import ai.tessary.evals.rca.RcaDtos.RuledOutCheck.Assessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The validator every {@link AgenticRcaEngine} run passes through — so the receipt whitelists
 * (both windows' trace ids, measured check ids), the verdict normalization, the cross-window
 * burden-of-proof downgrade, and the {@code detailed_report} passthrough are pinned here once.
 */
class RcaSynthesisOutputTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> CHECKS = Set.of("serving_model", "failing_cohort_shape");
    private static final Set<String> NO_PRIOR = Set.of();

    @Test
    void parsesDetailedReportAndDropsHallucinatedReceipts() {
        String text = "{\"summary\":\"s\",\"verdict\":\"behavior_change\",\"detailed_report\":\"## Investigation\","
                + "\"hypotheses\":[{\"title\":\"t\",\"confidence\":\"high\",\"rationale\":\"r\","
                + "\"evidence_trace_ids\":[\"tr-prior\",\"tr-degraded\",\"tr-invented\"]}]}";

        RcaSynthesisOutput.Parsed out =
                RcaSynthesisOutput.parse(MAPPER, text, Set.of("tr-prior"), Set.of("tr-degraded"), CHECKS, "proj");

        assertEquals(RcaReportRow.Verdict.BEHAVIOR_CHANGE, out.verdict());
        assertEquals("## Investigation", out.detailedReport());
        // Ids from BOTH windows are citable; only the invented one is dropped.
        assertEquals(List.of("tr-prior", "tr-degraded"), out.hypotheses().get(0).evidenceTraceIds());
        assertNull(out.verdictNote());
    }

    @Test
    void crossWindowVerdictWithoutPriorEvidenceIsDowngraded() {
        // The exact failure this guards against: a traffic_shift "proven" entirely from degraded-window
        // traces, with the prior window characterized by inference ("must have been...").
        for (String verdict : List.of(RcaReportRow.Verdict.TRAFFIC_SHIFT, RcaReportRow.Verdict.BEHAVIOR_CHANGE)) {
            String text = "{\"summary\":\"s\",\"verdict\":\"" + verdict + "\",\"detailed_report\":\"## r\","
                    + "\"hypotheses\":[{\"title\":\"t\",\"confidence\":\"high\",\"rationale\":\"r\","
                    + "\"evidence_trace_ids\":[\"tr-degraded\"]}]}";

            RcaSynthesisOutput.Parsed out =
                    RcaSynthesisOutput.parse(MAPPER, text, Set.of("tr-prior"), Set.of("tr-degraded"), CHECKS, "proj");

            assertEquals(RcaReportRow.Verdict.INCONCLUSIVE, out.verdict());
            assertNotNull(out.verdictNote());
            assertTrue(out.verdictNote().contains(verdict), out.verdictNote());
        }
    }

    @Test
    void crossWindowVerdictWithPriorEvidenceStands() {
        String text = "{\"summary\":\"s\",\"verdict\":\"traffic_shift\",\"detailed_report\":\"## r\","
                + "\"hypotheses\":[{\"title\":\"t\",\"confidence\":\"high\",\"rationale\":\"r\","
                + "\"evidence_trace_ids\":[\"tr-prior\",\"tr-degraded\"]}]}";

        RcaSynthesisOutput.Parsed out =
                RcaSynthesisOutput.parse(MAPPER, text, Set.of("tr-prior"), Set.of("tr-degraded"), CHECKS, "proj");

        assertEquals(RcaReportRow.Verdict.TRAFFIC_SHIFT, out.verdict());
        assertNull(out.verdictNote());
    }

    @Test
    void emptyPriorWindowExemptsTheCrossWindowBurden() {
        // Nothing citable exists on the prior side — demanding a citation would make the verdict
        // unreachable, so it stands on the rest of its evidence.
        String text = "{\"summary\":\"s\",\"verdict\":\"traffic_shift\",\"detailed_report\":\"## r\","
                + "\"hypotheses\":[{\"title\":\"t\",\"confidence\":\"high\",\"rationale\":\"r\","
                + "\"evidence_trace_ids\":[\"tr-degraded\"]}]}";

        RcaSynthesisOutput.Parsed out =
                RcaSynthesisOutput.parse(MAPPER, text, NO_PRIOR, Set.of("tr-degraded"), CHECKS, "proj");

        assertEquals(RcaReportRow.Verdict.TRAFFIC_SHIFT, out.verdict());
        assertNull(out.verdictNote());
    }

    @Test
    void structuralVerdictsSurviveBecauseTheAgentVerifiesThemItself() {
        // The deterministic rule-outs this replaced owned definition_change/model_change and the
        // model's own claim was clamped away. The agent has the repo, so it now owns them — and they
        // are single-window claims, so no prior-citation burden applies.
        for (String verdict : List.of(RcaReportRow.Verdict.DEFINITION_CHANGE, RcaReportRow.Verdict.MODEL_CHANGE)) {
            RcaSynthesisOutput.Parsed out = RcaSynthesisOutput.parse(
                    MAPPER,
                    "{\"summary\":\"s\",\"verdict\":\"" + verdict + "\",\"hypotheses\":[]}",
                    Set.of("tr-prior"),
                    Set.of(),
                    CHECKS,
                    "proj");
            assertEquals(verdict, out.verdict());
        }
    }

    @Test
    void unknownVerdictFallsToInconclusiveAndMissingReportIsNull() {
        RcaSynthesisOutput.Parsed out = RcaSynthesisOutput.parse(
                MAPPER,
                "{\"summary\":\"s\",\"verdict\":\"vibes\",\"hypotheses\":[]}",
                NO_PRIOR,
                Set.of(),
                CHECKS,
                "proj");

        assertEquals(RcaReportRow.Verdict.INCONCLUSIVE, out.verdict());
        assertNull(out.detailedReport());
    }

    @Test
    void keepsAssessmentsOfMeasuredChecksAndDropsInventedOnes() {
        String text = "{\"summary\":\"s\",\"verdict\":\"behavior_change\",\"hypotheses\":[],\"checklist\":["
                + "{\"check\":\"serving_model\",\"assessment\":\"ruled_out\",\"detail\":\"whitespace bump\"},"
                + "{\"check\":\"failing_cohort_shape\",\"assessment\":\"explains\",\"detail\":\"40% errored\"},"
                + "{\"check\":\"vibe_check\",\"assessment\":\"explains\",\"detail\":\"invented\"}]}";

        RcaSynthesisOutput.Parsed out = RcaSynthesisOutput.parse(MAPPER, text, NO_PRIOR, Set.of(), CHECKS, "proj");

        assertEquals(2, out.checklist().size());
        assertTrue(out.checklist().stream().noneMatch(c -> "vibe_check".equals(c.check())));
        assertEquals(Assessment.RULED_OUT, out.checklist().get(0).assessment());
        assertEquals(Assessment.EXPLAINS, out.checklist().get(1).assessment());
    }

    @Test
    void unrecognizedAssessmentBecomesUnknownRatherThanASilentPass() {
        String text = "{\"summary\":\"s\",\"verdict\":\"inconclusive\",\"hypotheses\":[],\"checklist\":["
                + "{\"check\":\"failing_cohort_shape\",\"assessment\":\"probably fine\",\"detail\":\"d\"}]}";

        RcaSynthesisOutput.Parsed out = RcaSynthesisOutput.parse(MAPPER, text, NO_PRIOR, Set.of(), CHECKS, "proj");

        assertEquals(Assessment.UNKNOWN, out.checklist().get(0).assessment());
    }

    /**
     * The production failure this guards against: a 4m48s / $0.80 investigation was thrown away at its
     * last step because the reply carried one field the records do not declare. The response schema
     * does not set {@code additionalProperties: false}, so a model is free to add keys, and every
     * other validation here already DROPS what it cannot accept (hallucinated trace ids, invented
     * check ids) rather than failing the run — binding was the one place that did the opposite.
     */
    @Test
    void unexpectedFieldsAnywhereInTheTreeDoNotSinkTheRun() {
        String text = "{\"summary\":\"s\",\"verdict\":\"model_change\",\"detailed_report\":\"## r\","
                + "\"confidence_overall\":\"high\","
                + "\"hypotheses\":[{\"title\":\"t\",\"confidence\":\"high\",\"rationale\":\"r\","
                + "\"evidence_trace_ids\":[\"tr-degraded\"],\"supporting_commits\":[\"abc123\"]}],"
                + "\"checklist\":[{\"check\":\"serving_model\",\"assessment\":\"explains\",\"detail\":\"d\","
                + "\"weight\":0.8}]}";

        RcaSynthesisOutput.Parsed out =
                RcaSynthesisOutput.parse(MAPPER, text, NO_PRIOR, Set.of("tr-degraded"), CHECKS, "proj");

        assertEquals(RcaReportRow.Verdict.MODEL_CHANGE, out.verdict());
        assertEquals("## r", out.detailedReport());
        assertEquals(List.of("tr-degraded"), out.hypotheses().get(0).evidenceTraceIds());
        assertEquals(1, out.checklist().size());
    }

    /**
     * The salvage path. {@code E2bRcaSandbox} now hands over the envelope's schema-validated
     * {@code structured_output} first, so a well-formed run never reaches this; it covers the envelope
     * that carries no structured output and whose {@code result} the model wrapped in prose.
     */
    @Test
    void aReplyWrappedInProseAndAFenceIsStillRead() {
        String text = "Here is the completed analysis:\n\n```json\n"
                + "{\"summary\":\"s\",\"verdict\":\"inconclusive\",\"detailed_report\":\"## r\","
                + "\"hypotheses\":[],\"checklist\":[]}\n```";

        RcaSynthesisOutput.Parsed out = RcaSynthesisOutput.parse(MAPPER, text, NO_PRIOR, Set.of(), CHECKS, "proj");

        assertEquals(RcaReportRow.Verdict.INCONCLUSIVE, out.verdict());
        assertEquals("s", out.summary());
        assertEquals("## r", out.detailedReport());
    }

    @Test
    void nonJsonFailsClosed() {
        EvalsException ex = assertThrows(
                EvalsException.class,
                () -> RcaSynthesisOutput.parse(MAPPER, "just prose", NO_PRIOR, Set.of(), CHECKS, "proj"));
        assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
    }
}
