// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import org.jspecify.annotations.Nullable;

/**
 * One case ({@code eval_case}) — a detection that crossed its detector's bar, in the one shape that
 * reaches a human. The object is <b>cause-neutral</b>: {@link #detector} is the only thing that says
 * what noticed, and every field below it is written the same way whether a CUSUM spell, a
 * behaviour-drift finding or a classifier detection produced it.
 *
 * <p>Identity is {@code (projectId, detector, subjectKind, subjectId, metric)} — the same subject key
 * {@code rca_report} already carries, so an RCA run from a case snapshots the case's own subject with
 * no translation. {@code ux_eval_case_live} enforces one non-resolved case per key.
 *
 * <p>{@link #basis} carries the detector's own account of why this crossed. Detectors do not share a
 * bar and are not made to: CUSUM fires on sustained change against a frozen baseline at any absolute
 * level, a classifier fires on a threshold. Flattening those into one number would make the ranked
 * list lie, so the comparable quantity is {@link #severity} (ordering only) and the honest one is
 * {@code basis} plus the value triple.
 */
public record CaseRow(
        String id,
        String projectId,
        long seq,
        String detector,
        String subjectKind,
        String subjectId,
        String subjectLabel,
        @Nullable String callSiteId,
        String metric,
        @Nullable String findingId,
        String state,
        String title,
        String basis,
        double severity,
        String onsetAt,
        @Nullable Double currentValue,
        @Nullable Double baselineValue,
        @Nullable Double delta,
        String openedAt,
        String lastSeenAt,
        @Nullable String resolvedAt,
        @Nullable String resolution,
        @Nullable String resolutionReason,
        @Nullable String resolvedBy,
        @Nullable String mutedAt,
        @Nullable String mutedBy,
        String updatedAt) {

    /** The display id a human quotes: {@code C-118}. */
    public String reference() {
        return "C-" + seq;
    }

    /** {@code detector} values — what noticed. New detectors add a constant here and a
     *  {@link CaseSource} implementation; nothing else in the slice changes. */
    public static final class Detector {
        private Detector() {}

        // GRADER_DEGRADATION ("grader_degradation") was here — a grader in a CUSUM-detected degraded
        // spell. The CUSUM watcher went first, and Track A took grading with it; changeset 0016 deletes
        // the surviving rows and narrows eval_case_detector_check, so the string is rejected at the
        // database rather than accepted into a detector nothing produces.

        /** A behaviour-drift finding that survived triage ({@code classifier/Behavior*}). */
        public static final String BEHAVIOR_DRIFT = "behavior_drift";

        /**
         * A user classifier's detections over its configured threshold ({@code classifier/}).
         *
         * <p>Spelled {@code classifier_signal} until 0093, which folded it into the value 0089 had
         * already added for the same thing: a per-span finding's case is named for the classifier that
         * filed it, and for a user classifier that name is {@code classifier}.
         */
        public static final String CLASSIFIER = "classifier";

        /**
         * A metric-drift finding that survived triage ({@code classifier/MetricDrift*}) — one
         * bucket's duration or cost distribution sitting measurably away from its own earlier one.
         *
         * <p>Same gate as {@link #BEHAVIOR_DRIFT}, and for a sharper reason: metric-drift findings are
         * written with <b>no alert budget at all</b> so their operating point can be tuned against real
         * firings rather than a guessed number ({@code classifiers/metric_drift/PROGRAM.md} §9). That
         * decision is only survivable because the stream stops at the Classifiers page — Triage sees the
         * subset a repo-grounded Layer-2 run called a deviation, plus what a human ruled one directly.
         */
        public static final String METRIC_DRIFT = "metric_drift";

        /**
         * A tool's failure rate that survived triage. Same gate as {@link #METRIC_DRIFT} and
         * {@link #BEHAVIOR_DRIFT}: the detector's findings stream unbudgeted, and only a triage ruling
         * of {@code positive} — or a human pressing <em>Real deviation</em> — reaches Triage.
         */
        public static final String TOOL_ERROR = "tool_error";

        /**
         * An SOP-conformance finding that survived triage (a paid classifier since #841) —
         * an authored rule the agent satisfies measurably less often than its own reference period.
         * Same gate as the other triaged detectors: the expectation test streams findings to the
         * Classifiers page, and only a Layer-2 ruling of deviation reaches Triage.
         */
        public static final String SOP_CONFORMANCE = "sop_conformance";
    }

    /** {@code subject_kind} values — what the case is about. */
    public static final class SubjectKind {
        private SubjectKind() {}

        // GRADER ("grader") was here; see Detector above.
        public static final String BEHAVIOR_PROFILE = "behavior_profile";
        public static final String CLASSIFIER = "classifier";

        /**
         * One (bucket × measure) window state — {@code metric_baseline}. Its own kind rather than
         * {@link #CLASSIFIER} because the subject a metric-drift case is about is not the switch that
         * noticed, it is the population: {@code discover-sales-prospects}' turn durations, which keep
         * their identity across every deploy, every re-pin and every window that closes over them.
         */
        public static final String METRIC_BASELINE = "metric_baseline";

        /**
         * One tool, keyed by its {@code ActionSymbol}. The subject of a tool-error case, and deliberately
         * the TOOL rather than the finding: a rise and a later fall are two causes to explain but one
         * thing to page about, so both collapse onto one live case
         * ({@code classifiers/tool_error/PROGRAM.md} §6.1).
         */
        public static final String TOOL = "tool";

        /**
         * One authored SOP rule, keyed by its slug ({@code conformance_rule.rule_key}). The RULE rather
         * than the finding, for the tool-error reason: a drift, a recovery and a later re-drift are
         * causes to explain, but one obligation to page about.
         */
        public static final String SOP_RULE = "sop_rule";
    }

    /** {@code state} values. Muted is live, not closed — see {@code ux_eval_case_live}. */
    public static final class State {
        private State() {}

        public static final String OPEN = "open";
        public static final String RESOLVED = "resolved";
        public static final String MUTED = "muted";
    }

    /** {@code resolution} values — who closed it. */
    public static final class Resolution {
        private Resolution() {}

        /** The detection stopped firing for a full window; closed silently by the reconciler. */
        public static final String RECOVERED = "recovered";

        /** A human closed it, with a required one-line reason. */
        public static final String HUMAN = "human";

        /**
         * A human ruled the shift legitimate AND the detector's reference was moved to include it, so the
         * level it fired on is the new baseline.
         *
         * <p>Distinct from {@link #HUMAN} because the difference is the one a later reader most needs.
         * A case closed as {@code human} leaves the detector's bar exactly where it was, so an unchanged
         * population opens another case tomorrow; {@code absorbed} says the bar moved, and it will not.
         */
        public static final String ABSORBED = "absorbed";
    }

    public boolean isLive() {
        return !State.RESOLVED.equals(state);
    }
}
