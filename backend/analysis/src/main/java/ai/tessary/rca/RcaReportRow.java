// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.catalog.BuiltInDetector;
import org.jspecify.annotations.Nullable;

/**
 * One immutable RCA report ({@code rca_report}), about one finding. The subject/metric/window/value
 * columns are the snapshot taken at trigger time — trigger-time truth, never recomputed, so a report
 * still reads correctly after the finding it analysed has been resolved (and reports written before
 * {@code finding_id} existed analysed a CUSUM mover, which is why that column is nullable).
 * {@code ruledOut}/{@code hypotheses}/{@code causes} are jsonb blobs of {@link RcaDtos.RuledOutCheck}/
 * {@link RcaDtos.Hypothesis}/{@link RcaDtos.Cause}; {@code causes} is set only on a report that
 * {@linkplain ReportKind#namesCauses names causes}, which writes no hypotheses. {@code status} is stamped
 * by the worker at completion; the wire view reads the live queue status off the joined {@code job} row
 * (see {@link RcaReportRepository}), so an exhaustion-swept job can never leave a report reading
 * "claimed" forever.
 */
public record RcaReportRow(
        String id,
        String jobId,
        String subjectKind,
        String subjectId,
        String subjectLabel,
        @Nullable String callSiteId,
        String metric,
        String reportKind,
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
        @Nullable String causes,
        @Nullable String detailedReport,
        String engine,
        @Nullable Boolean repoAvailable,
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
        /** A report that {@linkplain ReportKind#namesCauses names causes} and grouped the flagged sessions or
         *  answers into at least one cause citing them. */
        public static final String CAUSES_IDENTIFIED = "causes_identified";
        /** A report that {@linkplain ReportKind#namesCauses names causes} and found no agent behaviour the
         *  flagged sessions or answers share. */
        public static final String NO_CAUSE_FOUND = "no_cause_found";
    }

    /** {@code report_kind} values — which question the report answers, fixed at trigger time. */
    public static final class ReportKind {
        private ReportKind() {}

        /** What change moved a measured number. Every classifier but Frustration and Groundedness. */
        public static final String METRIC_MOVEMENT = "metric_movement";
        /** What the agent did that frustrated the users of one call site. There is no baseline side. */
        public static final String FRUSTRATION_CAUSES = "frustration_causes";
        /** Why one call site's answers stopped being supported by the documents it retrieved. There is no
         *  baseline side either: the receipts are the traces with a flagged answer. */
        public static final String GROUNDEDNESS_CAUSES = "groundedness_causes";

        /** The kind of report a finding filed by {@code classifierKey} gets. */
        public static String forClassifier(String classifierKey) {
            if (BuiltInDetector.Kind.FRUSTRATION.equals(classifierKey)) return FRUSTRATION_CAUSES;
            if (BuiltInDetector.Kind.GROUNDEDNESS.equals(classifierKey)) return GROUNDEDNESS_CAUSES;
            return METRIC_MOVEMENT;
        }

        /** True for the kinds that rank causes against a learned rate rather than explain a movement: no
         *  baseline side, no two-sided checklist, and no hypotheses. */
        public static boolean namesCauses(String reportKind) {
            return FRUSTRATION_CAUSES.equals(reportKind) || GROUNDEDNESS_CAUSES.equals(reportKind);
        }
    }

    /** {@code engine} values code writes. The column also allows the older {@code synthesis}.
     *  {@code detailedReport} is only ever non-null on {@link #AGENTIC} reports (the agent's full
     *  markdown investigation). */
    public static final class Engine {
        private Engine() {}

        public static final String AGENTIC = "agentic";
    }
}
