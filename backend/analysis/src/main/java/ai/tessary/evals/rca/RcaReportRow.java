// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.rca;

import org.jspecify.annotations.Nullable;

/**
 * One immutable RCA report ({@code rca_report}), about one finding. The subject/metric/window/value
 * columns are the snapshot taken at trigger time — trigger-time truth, never recomputed, so a report
 * still reads correctly after the finding it analysed has been resolved (and reports written before
 * {@code finding_id} existed analysed a CUSUM mover, which is why that column is nullable).
 * {@code ruledOut}/{@code hypotheses} are jsonb blobs of {@link RcaDtos.RuledOutCheck}/
 * {@link RcaDtos.Hypothesis}. {@code status} is stamped by the worker at completion; the wire view
 * reads the live queue status off the joined {@code job} row (see {@link RcaReportRepository}), so an
 * exhaustion-swept job can never leave a report reading "claimed" forever.
 */
public record RcaReportRow(
        String id,
        String projectId,
        String jobId,
        String subjectKind,
        String subjectId,
        String subjectLabel,
        @Nullable String callSiteId,
        String metric,
        String windowFrom,
        String windowSplit,
        String windowTo,
        double currentValue,
        double priorValue,
        double delta,
        String status,
        @Nullable String verdict,
        @Nullable String summary,
        @Nullable String ruledOut,
        @Nullable String hypotheses,
        @Nullable String detailedReport,
        String engine,
        String createdAt,
        @Nullable String completedAt) {

    /** {@code verdict} values — what the analysis concluded the movement was. */
    public static final class Verdict {
        private Verdict() {}

        public static final String DEFINITION_CHANGE = "definition_change";
        public static final String MODEL_CHANGE = "model_change";
        public static final String TRAFFIC_SHIFT = "traffic_shift";
        public static final String BEHAVIOR_CHANGE = "behavior_change";
        public static final String INCONCLUSIVE = "inconclusive";
    }

    /** {@code engine} values — which synthesis lane produced the report. {@code detailedReport} is
     *  only ever non-null on {@link #AGENTIC} reports (the agent's full markdown investigation). */
    public static final class Engine {
        private Engine() {}

        public static final String SYNTHESIS = "synthesis";
        public static final String AGENTIC = "agentic";
    }
}
