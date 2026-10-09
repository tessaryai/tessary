// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.catalog.BuiltInDetector;
import org.jspecify.annotations.Nullable;

/**
 * One immutable RCA report ({@code rca_report}), about one finding. The subject/metric/window/value
 * columns are the snapshot taken at trigger time — trigger-time truth, never recomputed, so a report
 * still reads correctly after the finding it analysed has been resolved (and reports written before
 * {@code finding_id} existed analysed a CUSUM mover, which is why that column is nullable).
 * {@code ruledOut}/{@code causes} are jsonb blobs of {@link RcaDtos.RuledOutCheck}/{@link RcaDtos.Cause},
 * every report kind writing the same cause shape. {@code hypotheses} is read only: a metric report written
 * before that stored its causes there, in an older shape {@link RcaDtos#causesOf} maps. {@code status} is stamped
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

    /**
     * {@code verdict} values code writes. Reports written before one prompt served every classifier also
     * hold {@code definition_change}, {@code model_change}, {@code traffic_shift}, {@code behavior_change} and
     * {@code inconclusive}; they are read as stored.
     */
    public static final class Verdict {
        private Verdict() {}

        /** At least one cause reached medium confidence and cites this finding's evidence. */
        public static final String CAUSES_IDENTIFIED = "causes_identified";
        /** No cause reached medium confidence. */
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

        /** True for the kinds measured as a rate against a learned one, whose report header reads as that
         *  rate rather than as the classifier's asserted severity. */
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
