// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.rca.RcaDtos.Attribution;
import ai.tessary.rca.RcaDtos.Cause;
import ai.tessary.rca.RcaDtos.RcaReportView;
import ai.tessary.rca.RcaDtos.RuledOutCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** How a stored report reads back onto the wire. */
class RcaDtosTest {

    /** Strict, like the application's own mapper: an unknown property fails a plain bind. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Catches one unreadable list column (a truncated write, a blob an older build shaped differently) failing
     * the whole report read, which would leave the report page, and every list it appears in, unreadable. The
     * column reads as empty and its readable siblings still come back.
     */
    @Test
    void anUnreadableListColumnReadsAsEmptyWithoutLosingItsSiblings() {
        RcaReportView view = RcaReportView.of(
                row(
                        RcaReportRow.ReportKind.METRIC_MOVEMENT,
                        "s",
                        "[{\"check\":",
                        null,
                        "[{\"title\":\"New model\",\"confidence\":\"high\",\"what_changed\":\"r\"}]"),
                MAPPER);

        assertEquals(List.of(), view.ruledOut());
        assertEquals("New model", view.causes().get(0).title());
    }

    /**
     * Catches a metric report written before every kind shared one cause shape reading as having no causes: its
     * causes were stored as {@code hypotheses}, with {@code rationale} where {@code what_changed} is now.
     */
    @Test
    void anOldMetricReportsHypothesesReadAsItsCauses() {
        RcaReportView view = RcaReportView.of(
                row(
                        RcaReportRow.ReportKind.METRIC_MOVEMENT,
                        "s",
                        "[]",
                        "[{\"title\":\"New model\",\"confidence\":\"high\",\"rationale\":\"r\","
                                + "\"evidence_trace_ids\":[\"t1\"]}]",
                        null),
                MAPPER);

        assertEquals(
                List.of(new Cause("New model", "high", null, null, "r", null, null, null, List.of("t1"), List.of(), 0)),
                view.causes());
    }

    /**
     * Catches an old frustration cause vanishing: its {@code what_the_agent_did}, {@code fix_suggestion},
     * {@code sessions_affected} and {@code traces_affected} are unknown to the current shape, and the report's
     * lists read empty on any parse failure.
     */
    @Test
    void anOldFrustrationCauseMapsOntoTheCurrentShape() {
        RcaReportView view = RcaReportView.of(
                row(
                        RcaReportRow.ReportKind.FRUSTRATION_CAUSES,
                        "s",
                        "[]",
                        null,
                        "[{\"title\":\"Ignores the attachment\",\"what_the_agent_did\":\"w\",\"sessions_affected\":4,"
                                + "\"traces_affected\":2,\"evidence_session_ids\":[\"s-1\"],"
                                + "\"evidence_trace_ids\":[\"tr-1\",\"tr-2\"],\"attribution\":{\"kind\":\"prompt\","
                                + "\"path\":\"agent/prompt.md\",\"commit\":null,\"excerpt\":null},"
                                + "\"fix_suggestion\":\"f\",\"confidence\":\"medium\"}]"),
                MAPPER);

        assertEquals(
                List.of(new Cause(
                        "Ignores the attachment",
                        "medium",
                        null,
                        null,
                        "w",
                        null,
                        "f",
                        new Attribution("prompt", "agent/prompt.md", null, null),
                        List.of("tr-1", "tr-2"),
                        List.of("s-1"),
                        4)),
                view.causes());
    }

    /**
     * Catches a report written before one prompt served every classifier failing to read or losing data: its
     * causes have {@code low} confidence and {@code attribution.kind} and no {@code change} or {@code type},
     * and its {@code ruled_out} holds checklist entries assessed {@code contributing}, {@code explains} and
     * {@code unknown}.
     */
    @Test
    void anOldFormatReportReadsWithEveryCauseAndChecklistEntry() {
        RcaReportView view = RcaReportView.of(
                row(
                        RcaReportRow.ReportKind.METRIC_MOVEMENT,
                        "s",
                        "[{\"check\":\"serving_model\",\"passed\":false,\"detail\":\"d1\","
                                + "\"assessment\":\"contributing\",\"measurement\":\"m1\",\"question\":\"q1\"},"
                                + "{\"check\":\"failing_cohort_shape\",\"passed\":false,\"detail\":\"d2\","
                                + "\"assessment\":\"explains\",\"measurement\":\"m2\",\"question\":null},"
                                + "{\"check\":\"traffic_mix\",\"passed\":false,\"detail\":\"d3\","
                                + "\"assessment\":\"unknown\",\"measurement\":\"m3\"}]",
                        null,
                        "[{\"title\":\"New model\",\"confidence\":\"low\",\"what_changed\":\"w\","
                                + "\"attribution\":{\"kind\":\"model\",\"path\":\"cfg.yaml\",\"commit\":\"abc\","
                                + "\"excerpt\":null},\"evidence_trace_ids\":[\"t1\"],\"evidence_session_ids\":[],"
                                + "\"affected_count\":3}]"),
                MAPPER);

        assertEquals(
                List.of(new Cause(
                        "New model",
                        "low",
                        null,
                        null,
                        "w",
                        null,
                        null,
                        new Attribution("model", "cfg.yaml", "abc", null),
                        List.of("t1"),
                        List.of(),
                        3)),
                view.causes());
        assertEquals(
                List.of(
                        new RuledOutCheck("serving_model", false, "d1", "contributing", "m1", "q1"),
                        new RuledOutCheck("failing_cohort_shape", false, "d2", "explains", "m2", null),
                        new RuledOutCheck("traffic_mix", false, "d3", "unknown", "m3", null)),
                view.ruledOut());
    }

    /**
     * Catches a current report losing what only it carries: a cause's {@code change} and {@code type}, an
     * attribution with no {@code kind}, and a ruled-out sentence with no {@code detail}.
     */
    @Test
    void aNewFormatReportReadsItsChangeTypeAndRuledOutSentences() {
        RcaReportView view = RcaReportView.of(
                row(
                        RcaReportRow.ReportKind.FRUSTRATION_CAUSES,
                        "s",
                        "[{\"check\":\"ruled_out_1\",\"passed\":true,\"detail\":null,"
                                + "\"assessment\":\"ruled_out\",\"measurement\":null,"
                                + "\"question\":\"The model did not change.\"}]",
                        null,
                        "[{\"title\":\"Ignores the attachment\",\"confidence\":\"medium\",\"change\":\"standing\","
                                + "\"type\":\"prompt\",\"what_changed\":\"w\",\"attribution\":{\"kind\":null,"
                                + "\"path\":\"agent/prompt.md\",\"commit\":null,\"excerpt\":\"e\"},"
                                + "\"evidence_trace_ids\":[],\"evidence_session_ids\":[\"s-1\"],\"affected_count\":2}]"),
                MAPPER);

        Cause cause = view.causes().get(0);
        assertEquals("standing", cause.change());
        assertEquals("prompt", cause.type());
        assertEquals(new Attribution(null, "agent/prompt.md", null, "e"), cause.attribution());
        assertEquals(List.of(RuledOutCheck.ruledOut(1, "The model did not change.")), view.ruledOut());
    }

    /** Catches an old groundedness cause counting the sessions it never had (always 0) instead of its traces. */
    @Test
    void anOldGroundednessCauseCountsItsTraces() {
        RcaReportView view = RcaReportView.of(
                row(
                        RcaReportRow.ReportKind.GROUNDEDNESS_CAUSES,
                        "s",
                        "[]",
                        null,
                        "[{\"title\":\"Stale index\",\"what_the_agent_did\":\"w\",\"sessions_affected\":0,"
                                + "\"traces_affected\":5,\"evidence_session_ids\":[],\"evidence_trace_ids\":[\"tr-1\"],"
                                + "\"attribution\":null,\"fix_suggestion\":\"f\",\"confidence\":\"high\"}]"),
                MAPPER);

        assertEquals(5, view.causes().get(0).affectedCount());
    }

    /**
     * Catches JSON in the Triage caption: an older run whose summary was blank stored the agent's whole reply as
     * its summary. It reads as the first cause's title, or as no summary.
     */
    @Test
    void aRawReplyStoredAsTheSummaryReadsAsTheFirstCause() {
        String cause = "[{\"title\":\"New model\",\"confidence\":\"high\"}]";

        assertEquals(
                "New model",
                RcaReportView.of(
                                row(RcaReportRow.ReportKind.METRIC_MOVEMENT, "{\"summary\":\"\"}", "[]", null, cause),
                                MAPPER)
                        .summary());
        assertNull(RcaReportView.of(
                        row(RcaReportRow.ReportKind.METRIC_MOVEMENT, "{\"summary\":\"\"}", "[]", null, null), MAPPER)
                .summary());
        assertEquals(
                "Plain.",
                RcaReportView.of(row(RcaReportRow.ReportKind.METRIC_MOVEMENT, "Plain.", "[]", null, cause), MAPPER)
                        .summary());
    }

    private static RcaReportRow row(
            String reportKind,
            @Nullable String summary,
            @Nullable String ruledOut,
            @Nullable String hypotheses,
            @Nullable String causes) {
        return new RcaReportRow(
                "rpt-1",
                "job-1",
                "finding",
                "fnd-1",
                "label",
                null,
                "behavior_drift",
                reportKind,
                "2026-05-01T00:00:00Z",
                "2026-05-04T00:00:00Z",
                "2026-05-08T00:00:00Z",
                0.3,
                0.02,
                0.28,
                "done",
                "model_change",
                summary,
                ruledOut,
                hypotheses,
                causes,
                "## r",
                RcaReportRow.Engine.AGENTIC,
                true,
                "2026-05-08T01:00:00Z",
                "2026-05-08T01:05:00Z");
    }
}
