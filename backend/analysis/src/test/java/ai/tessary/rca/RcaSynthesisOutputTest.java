// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.open.errors.RcaError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.rca.RcaDtos.RuledOutCheck.Assessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The validator every {@link AgenticRcaEngine} run passes through: receipt whitelists, verdict normalization, the
 * cross-window burden-of-proof downgrade, and {@code detailed_report} passthrough.
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
        // Both windows' ids are citable; only the invented one drops.
        assertEquals(List.of("tr-prior", "tr-degraded"), out.hypotheses().get(0).evidenceTraceIds());
        assertNull(out.verdictNote());
    }

    @Test
    void crossWindowVerdictWithoutPriorEvidenceIsDowngraded() {
        // The failure guarded: a traffic_shift "proven" from degraded-window traces alone, the prior window inferred.
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
        // Nothing citable on the prior side, so demanding a citation would make the verdict unreachable.
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
        // The agent has the repo, so it owns these verdicts; single-window, so no prior-citation burden.
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
     * A 4m48s, $0.80 investigation was thrown away at its last step over one undeclared field. The schema allows
     * extra keys, and every other check here drops what it cannot accept.
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
     * The salvage path, for an envelope with no {@code structured_output} whose {@code result} is wrapped in prose.
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

    /**
     * When the brace-sliced salvage does not bind either, the run fails closed as UPSTREAM_FAILED with the first
     * parse failure.
     */
    @Test
    void proseAroundAnUnbindableObjectFailsClosedWithTheFirstFailure() {
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> RcaSynthesisOutput.parse(
                        MAPPER, "The answer is {verdict: inconclusive} as above.", NO_PRIOR, Set.of(), CHECKS, "proj"));

        assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
        assertTrue(
                ex.getCause() instanceof com.fasterxml.jackson.core.JsonProcessingException,
                String.valueOf(ex.getCause()));
    }

    /** A blank or backwards-braced reply fails the run rather than binding to an empty report. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"   ", "} the braces are backwards {"})
    void aReplyWithNothingToBindFailsClosed(String reply) {
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> RcaSynthesisOutput.parse(MAPPER, reply, NO_PRIOR, Set.of(), CHECKS, "proj"));
        assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
    }

    /**
     * Optional fields null or blank degrade rather than fail: blank summary falls back to the reply, blank report to
     * none, untitled hypothesis dropped, missing confidence reads low, missing lists read empty, a checkless
     * assessment dropped, and a repeat does not override the first.
     */
    @Test
    void aSparseMetricReplyDegradesFieldByField() {
        String reply = "{\"summary\":\"  \",\"verdict\":null,\"detailed_report\":\"  \","
                + "\"hypotheses\":[{\"title\":null},{\"title\":\"Canary model\",\"confidence\":null,"
                + "\"rationale\":null,\"evidence_trace_ids\":null}],"
                + "\"checklist\":[{\"check\":null,\"assessment\":\"explains\"},"
                + "{\"check\":\"serving_model\",\"assessment\":\"explains\",\"detail\":null},"
                + "{\"check\":\"serving_model\",\"assessment\":\"ruled_out\",\"detail\":\"second\"}]}";

        RcaSynthesisOutput.Parsed out = RcaSynthesisOutput.parse(MAPPER, reply, NO_PRIOR, Set.of(), CHECKS, "proj");

        assertEquals(reply, out.summary());
        assertEquals(RcaReportRow.Verdict.INCONCLUSIVE, out.verdict());
        assertNull(out.detailedReport());
        assertEquals(List.of(new RcaDtos.Hypothesis("Canary model", "low", "", List.of())), out.hypotheses());
        assertEquals(
                List.of(new RcaSynthesisOutput.ChecklistAssessment("serving_model", Assessment.EXPLAINS, "")),
                out.checklist());
    }

    /**
     * The same for a cause reply: blank-titled causes drop, a twice-cited session counts once, an unknown
     * attribution kind is not passed through, absent prose does not fail, and no causes key finds nothing.
     */
    @Test
    void aSparseCauseReplyDegradesFieldByField() {
        String frustrationReply = "{\"verdict\":\"causes_identified\",\"causes\":[{\"title\":\" \"},"
                + "{\"title\":\"Asks twice\",\"evidence_session_ids\":[\"s-1\",\"s-1\",\"s-9\"],"
                + "\"attribution\":{\"kind\":null,\"path\":\" \"}}]}";

        RcaSynthesisOutput.Parsed frustration =
                RcaSynthesisOutput.parseFrustration(MAPPER, frustrationReply, TURNS, SESSIONS, COHORT, "proj");

        assertEquals(frustrationReply, frustration.summary());
        assertNull(frustration.detailedReport());
        assertEquals(
                List.of(new RcaDtos.Cause(
                        "Asks twice",
                        "",
                        1,
                        0,
                        List.of("s-1"),
                        List.of(),
                        new RcaDtos.Attribution(RcaDtos.Attribution.UNKNOWN, null, null, null),
                        "",
                        "low")),
                frustration.causes());

        RcaSynthesisOutput.Parsed groundedness = RcaSynthesisOutput.parseGroundedness(
                MAPPER,
                "{\"verdict\":\"no_cause_found\",\"causes\":[{\"title\":\"One document\","
                        + "\"evidence_trace_ids\":[\"tr-1\"]}]}",
                TURNS,
                COHORT,
                "proj");
        assertEquals(
                List.of(new RcaDtos.Cause("One document", "", 0, 1, List.of(), List.of("tr-1"), null, "", "low")),
                groundedness.causes());

        assertEquals(
                List.of(),
                RcaSynthesisOutput.parseGroundedness(MAPPER, "{\"verdict\":\"no_cause_found\"}", TURNS, COHORT, "proj")
                        .causes());
    }

    @Test
    void nonJsonFailsClosed() {
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> RcaSynthesisOutput.parse(MAPPER, "just prose", NO_PRIOR, Set.of(), CHECKS, "proj"));
        assertEquals(RcaError.UPSTREAM_FAILED, ex.error());
    }

    private static final Set<String> SESSIONS = Set.of("s-1", "s-2", "s-3");
    private static final Set<String> TURNS = Set.of("tr-1", "tr-2", "tr-3");
    private static final Set<String> COHORT = Set.of("failing_cohort_shape");

    private static String cause(String title, int affected, String sessions, String traces) {
        return "{\"title\":\"" + title + "\",\"what_the_agent_did\":\"w\",\"sessions_affected\":" + affected
                + ",\"evidence_session_ids\":[" + sessions + "],\"evidence_trace_ids\":[" + traces + "],"
                + "\"attribution\":{\"kind\":\"prompt\",\"path\":\"agent/prompt.md\",\"commit\":\"abc123\","
                + "\"excerpt\":\"Never ask twice.\"},\"fix_suggestion\":\"f\",\"confidence\":\"medium\"}";
    }

    private static String frustration(String verdict, String... causes) {
        return "{\"summary\":\"s\",\"verdict\":\"" + verdict + "\",\"detailed_report\":\"## r\","
                + "\"checklist\":[],\"causes\":[" + String.join(",", causes) + "]}";
    }

    @Test
    void frustrationCausesKeepOnlyThisFindingsSessionsAndTurns() {
        String text = frustration(
                "causes_identified",
                cause("Ignores the attachment", 2, "\"s-1\",\"s-9\",\"s-1\"", "\"tr-1\",\"tr-9\""));

        RcaSynthesisOutput.Parsed out =
                RcaSynthesisOutput.parseFrustration(MAPPER, text, TURNS, SESSIONS, COHORT, "proj");

        assertEquals(RcaReportRow.Verdict.CAUSES_IDENTIFIED, out.verdict());
        assertTrue(out.hypotheses().isEmpty(), "a frustration report writes causes, not hypotheses");
        RcaDtos.Cause c = out.causes().get(0);
        assertEquals(List.of("s-1"), c.evidenceSessionIds(), "unknown and repeated session ids are dropped");
        assertEquals(List.of("tr-1"), c.evidenceTraceIds(), "a trace outside the flagged turns is dropped");
        assertEquals(1, c.tracesAffected(), "the flagged turns it cites");
        assertEquals("prompt", c.attribution().kind());
        assertEquals("agent/prompt.md", c.attribution().path());
        assertNull(out.verdictNote());
    }

    @Test
    void causesIdentifiedWithNoSurvivingCauseIsDowngradedToNoCauseFound() {
        String text = frustration("causes_identified", cause("Invented", 5, "\"s-9\"", ""));

        RcaSynthesisOutput.Parsed out =
                RcaSynthesisOutput.parseFrustration(MAPPER, text, TURNS, SESSIONS, COHORT, "proj");

        assertEquals(RcaReportRow.Verdict.NO_CAUSE_FOUND, out.verdict());
        assertTrue(out.causes().isEmpty());
        assertNotNull(out.verdictNote());
        assertTrue(out.verdictNote().contains("causes_identified"), out.verdictNote());
    }

    @Test
    void frustrationVerdictsNormaliseToTheirOwnPair() {
        for (String raw : List.of("behavior_change", "inconclusive", "vibes")) {
            RcaSynthesisOutput.Parsed out =
                    RcaSynthesisOutput.parseFrustration(MAPPER, frustration(raw), TURNS, SESSIONS, COHORT, "proj");
            assertEquals(RcaReportRow.Verdict.NO_CAUSE_FOUND, out.verdict(), raw);
            assertNull(out.verdictNote(), "an unknown verdict is normalised, not downgraded: " + raw);
        }
        // The metric lane never accepts the frustration pair.
        RcaSynthesisOutput.Parsed metric = RcaSynthesisOutput.parse(
                MAPPER,
                "{\"summary\":\"s\",\"verdict\":\"causes_identified\",\"hypotheses\":[]}",
                NO_PRIOR,
                Set.of(),
                CHECKS,
                "proj");
        assertEquals(RcaReportRow.Verdict.INCONCLUSIVE, metric.verdict());
    }

    @Test
    void causesRankBySessionsAffectedAndNeverUnderCountTheirOwnCitations() {
        String text = frustration(
                "causes_identified",
                cause("Small", 1, "\"s-1\"", ""),
                cause("Undercounted", 0, "\"s-1\",\"s-2\",\"s-3\"", ""),
                cause("Big", 9, "\"s-2\"", ""));

        RcaSynthesisOutput.Parsed out =
                RcaSynthesisOutput.parseFrustration(MAPPER, text, TURNS, SESSIONS, COHORT, "proj");

        assertEquals(
                List.of("Big", "Undercounted", "Small"),
                out.causes().stream().map(RcaDtos.Cause::title).toList());
        assertEquals(3, out.causes().get(1).sessionsAffected(), "a cause affects at least the sessions it cites");
    }

    @Test
    void anUnknownAttributionKindAndConfidenceAreNormalised() {
        String text = frustration(
                "causes_identified",
                "{\"title\":\"t\",\"what_the_agent_did\":\"w\",\"sessions_affected\":1,"
                        + "\"evidence_session_ids\":[\"s-1\"],\"evidence_trace_ids\":[],"
                        + "\"attribution\":{\"kind\":\"vibes\",\"path\":\"\"},\"fix_suggestion\":\"f\","
                        + "\"confidence\":\"certain\"}");

        RcaDtos.Cause c = RcaSynthesisOutput.parseFrustration(MAPPER, text, TURNS, SESSIONS, COHORT, "proj")
                .causes()
                .get(0);

        assertEquals(RcaDtos.Attribution.UNKNOWN, c.attribution().kind());
        assertNull(c.attribution().path(), "a blank path is no path");
        assertEquals("low", c.confidence());
    }

    /** The finding's traces with a flagged answer: a groundedness report's only receipts. */
    private static final Set<String> FLAGGED = Set.of("tr-1", "tr-2", "tr-3");

    private static String groundedCause(String title, int affected, String traces) {
        return "{\"title\":\"" + title + "\",\"what_the_agent_did\":\"w\",\"traces_affected\":" + affected
                + ",\"evidence_trace_ids\":[" + traces + "],"
                + "\"attribution\":{\"kind\":\"code\",\"path\":\"rag/retrieve.py\",\"commit\":\"abc123\","
                + "\"excerpt\":\"top_k=1\"},\"fix_suggestion\":\"f\",\"confidence\":\"medium\"}";
    }

    @Test
    void aGroundednessCauseCitingNoFlaggedTraceIsDroppedEvenWithSessions() {
        String text = frustration(
                "causes_identified",
                "{\"title\":\"Sessions only\",\"what_the_agent_did\":\"w\",\"traces_affected\":4,"
                        + "\"evidence_session_ids\":[\"s-1\"],\"evidence_trace_ids\":[\"tr-9\"],"
                        + "\"fix_suggestion\":\"f\",\"confidence\":\"high\"}",
                groundedCause("Real", 1, "\"tr-2\""));

        RcaSynthesisOutput.Parsed out = RcaSynthesisOutput.parseGroundedness(MAPPER, text, FLAGGED, COHORT, "proj");

        assertEquals(
                List.of("Real"), out.causes().stream().map(RcaDtos.Cause::title).toList());
    }

    @Test
    void groundednessCausesIdentifiedWithNoSurvivingCauseIsDowngraded() {
        String text = frustration("causes_identified", groundedCause("Invented", 5, "\"tr-9\""));

        RcaSynthesisOutput.Parsed out = RcaSynthesisOutput.parseGroundedness(MAPPER, text, FLAGGED, COHORT, "proj");

        assertEquals(RcaReportRow.Verdict.NO_CAUSE_FOUND, out.verdict());
        assertTrue(out.causes().isEmpty());
        assertNotNull(out.verdictNote());
        assertTrue(out.verdictNote().contains("trace with a flagged answer"), out.verdictNote());
        assertTrue(out.verdictNote().contains("`witness` trace refs"), out.verdictNote());
    }

    @Test
    void groundednessCausesRankByTracesAffectedAndNeverUnderCountTheirOwnCitations() {
        String text = frustration(
                "causes_identified",
                groundedCause("Small", 1, "\"tr-1\""),
                groundedCause("Undercounted", 0, "\"tr-1\",\"tr-2\",\"tr-3\""),
                groundedCause("Big", 9, "\"tr-2\""));

        RcaSynthesisOutput.Parsed out = RcaSynthesisOutput.parseGroundedness(MAPPER, text, FLAGGED, COHORT, "proj");

        assertEquals(
                List.of("Big", "Undercounted", "Small"),
                out.causes().stream().map(RcaDtos.Cause::title).toList());
        assertEquals(3, out.causes().get(1).tracesAffected(), "a cause affects at least the traces it cites");
    }
}
