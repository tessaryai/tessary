// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.classifier.catalog.BuiltInDetector;
import org.jspecify.annotations.Nullable;

/**
 * One immutable RCA report. The subject, metric, window and value columns are a trigger-time snapshot, never
 * recomputed. {@code hypotheses} is read only, for older metric reports ({@link RcaDtos#causesOf}). The wire status
 * comes from the joined {@code job} row, so an exhaustion-swept job never leaves a report reading "claimed".
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
        @Nullable String coverage,
        String createdAt,
        @Nullable String completedAt) {

    /**
     * Older reports also hold {@code definition_change}, {@code model_change}, {@code traffic_shift},
     * {@code behavior_change} and {@code inconclusive}; they are read as stored.
     */
    public static final class Verdict {
        private Verdict() {}

        /** At least one cause reached medium confidence and cites this finding's evidence. */
        public static final String CAUSES_IDENTIFIED = "causes_identified";

        public static final String NO_CAUSE_FOUND = "no_cause_found";
    }

    /** Fixed at trigger time. */
    public static final class ReportKind {
        private ReportKind() {}

        /** Every classifier but Frustration, Groundedness and adapter-owned ones. */
        public static final String METRIC_MOVEMENT = "metric_movement";
        /** No baseline side. */
        public static final String FRUSTRATION_CAUSES = "frustration_causes";
        /** No baseline side either: the receipts are the traces with a flagged answer. */
        public static final String GROUNDEDNESS_CAUSES = "groundedness_causes";
        /** A rate against a reference, so the header reads that rate. */
        public static final String OWNED_CAUSES = "owned_causes";

        public static String forClassifier(String classifierKey) {
            if (BuiltInDetector.Kind.FRUSTRATION.equals(classifierKey)) return FRUSTRATION_CAUSES;
            if (BuiltInDetector.Kind.GROUNDEDNESS.equals(classifierKey)) return GROUNDEDNESS_CAUSES;
            return METRIC_MOVEMENT;
        }

        /** Kinds measured as a rate against a learned one, whose header reads as that rate. */
        public static boolean namesCauses(String reportKind) {
            return FRUSTRATION_CAUSES.equals(reportKind)
                    || GROUNDEDNESS_CAUSES.equals(reportKind)
                    || OWNED_CAUSES.equals(reportKind);
        }
    }

    /**
     * The column also allows the older {@code synthesis}. {@code detailedReport} is non-null only on {@link #AGENTIC}
     * reports.
     */
    public static final class Engine {
        private Engine() {}

        public static final String AGENTIC = "agentic";
    }
}
