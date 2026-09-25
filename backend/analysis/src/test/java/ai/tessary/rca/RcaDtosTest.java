// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.rca.RcaDtos.Hypothesis;
import ai.tessary.rca.RcaDtos.RcaReportView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How a stored report reads back onto the wire. */
class RcaDtosTest {

    /**
     * Catches one unreadable list column (a truncated write, a blob an older build shaped differently) failing
     * the whole report read, which would leave the report page, and every list it appears in, unreadable. The
     * column reads as empty and its readable siblings still come back.
     */
    @Test
    void anUnreadableListColumnReadsAsEmptyWithoutLosingItsSiblings() {
        RcaReportRow row = new RcaReportRow(
                "rpt-1",
                "job-1",
                "finding",
                "fnd-1",
                "label",
                null,
                "behavior_drift",
                RcaReportRow.ReportKind.METRIC_MOVEMENT,
                "2026-05-01T00:00:00Z",
                "2026-05-04T00:00:00Z",
                "2026-05-08T00:00:00Z",
                0.3,
                0.02,
                0.28,
                "done",
                RcaReportRow.Verdict.MODEL_CHANGE,
                "s",
                "[{\"check\":",
                "[{\"title\":\"New model\",\"confidence\":\"high\",\"rationale\":\"r\",\"evidence_trace_ids\":[\"t1\"]}]",
                null,
                "## r",
                RcaReportRow.Engine.AGENTIC,
                true,
                "2026-05-08T01:00:00Z",
                "2026-05-08T01:05:00Z");

        RcaReportView view = RcaReportView.of(row, new ObjectMapper());

        assertEquals(List.of(), view.ruledOut());
        assertEquals(List.of(new Hypothesis("New model", "high", "r", List.of("t1"))), view.hypotheses());
        assertEquals(List.of(), view.causes());
    }
}
