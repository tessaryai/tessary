// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import org.jspecify.annotations.Nullable;

/**
 * One case ({@code eval_case}): a detection that crossed its detector's bar, in the one shape that
 * reaches a human. It is cause-neutral: {@link #detector} is the only field that says what noticed,
 * and every other field is written the same way regardless of source.
 *
 * <p>Identity is {@code (projectId, detector, subjectKind, subjectId, metric)}, the same subject key
 * {@code rca_report} carries, so an RCA run from a case snapshots the case's subject with no
 * translation. {@code ux_eval_case_live} enforces one non-resolved case per key.
 *
 * <p>{@link #basis} carries the detector's own account of why this crossed; detectors don't share a
 * bar. A CUSUM spell fires on sustained change against a frozen baseline, a classifier fires on a
 * threshold. Flattening those into one number would make the ranked list lie, so ordering uses
 * {@link #severity} and the honest explanation is {@code basis} plus the value triple.
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
        /** How many findings this case holds — zero for an archived pre-{@code case_id} row. */
        long findingCount,
        /** The newest of this case's findings ({@code finding.case_id}, newest {@code created_at}
         *  first) — the one its header, its ruling and (until 1c's multi-finding follow-up) its RCA
         *  lane all read. Null only for an archived case from before {@code finding.case_id} existed. */
        @Nullable String latestFindingId,
        String state,
        /** When a person pressed <em>Run RCA</em> on this case, or null if nobody has. A locked case
         *  never gets a new finding joined to it — the cause's next positive opens a fresh case — but
         *  stays open/muted/resolved exactly as an unlocked one otherwise. */
        @Nullable String lockedAt,
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
        /** What a person said a resolved frustration or groundedness case was ({@link Disposition}); null otherwise. */
        @Nullable String disposition,
        @Nullable String mutedAt,
        @Nullable String mutedBy,
        String updatedAt) {

    /** The display id a human quotes: {@code C-118}. */
    public String reference() {
        return "C-" + seq;
    }

    /** {@code detector} values: what noticed. New detectors add a constant here and a
     *  {@link CaseSource} implementation; nothing else in the slice changes. */
    public static final class Detector {
        private Detector() {}

        /** A behaviour-drift finding that survived triage ({@code classifier/Behavior*}). */
        public static final String BEHAVIOR_DRIFT = "behavior_drift";

        /**
         * A user classifier's detections over its configured threshold ({@code classifier/}). A
         * per-span finding's case is named for the classifier that filed it.
         */
        public static final String CLASSIFIER = "classifier";

        /**
         * A metric-drift finding that survived triage ({@code classifier/MetricDrift*}): one
         * bucket's duration or cost distribution sitting measurably away from its own earlier one.
         *
         * <p>Same gate as {@link #BEHAVIOR_DRIFT}. Metric-drift findings stream with no alert
         * budget, so Triage only sees the subset a repo-grounded run called a deviation, plus what
         * a human ruled one directly.
         */
        public static final String METRIC_DRIFT = "metric_drift";

        /**
         * A tool's failure rate that survived triage. Same gate as {@link #METRIC_DRIFT} and
         * {@link #BEHAVIOR_DRIFT}: the detector's findings stream unbudgeted, and only a triage
         * ruling of {@code positive}, or a human pressing <em>Real deviation</em>, reaches Triage.
         */
        public static final String TOOL_ERROR = "tool_error";

        /**
         * An SOP-conformance finding that survived triage: an authored rule the agent satisfies
         * measurably less often than its own reference period. Same gate as the other triaged
         * detectors: only a Layer-2 ruling of deviation reaches Triage.
         */
        public static final String SOP_CONFORMANCE = "sop_conformance";

        /**
         * A secret-leak finding ruled positive, at arming and without triage when it is high
         * confidence: a credential
         * sitting in a stored output is a fact to rotate, not a claim to audit. See {@link
         * ai.tessary.classifier.secretleak}.
         */
        public static final String SECRET_LEAK = "secret_leak";

        /**
         * A call site's declared-schema failure rate that survived triage. Same gate as
         * {@link #TOOL_ERROR}: the detector's findings stream unbudgeted, and only a triage ruling of
         * {@code positive}, or a human pressing <em>Real deviation</em>, reaches Triage.
         */
        public static final String MALFORMED_OUTPUT = "malformed_output";

        /**
         * A call site whose share of conversations frustrated with the agent rose above the rate it learned.
         * No triage gate: each spell's finding is ruled positive when it is filed, and opens or joins this case
         * in the same transaction. See {@link FrustrationCaseSource}.
         */
        public static final String FRUSTRATION = "frustration";

        /**
         * A call site whose share of traces with a flagged answer rose above the rate it learned. Same gate
         * as {@link #MALFORMED_OUTPUT}: triage rules on the finding, and only a ruling of {@code positive},
         * or a human pressing <em>Real deviation</em>, reaches Triage. See {@link GroundednessCaseSource}.
         */
        public static final String GROUNDEDNESS = "groundedness";
    }

    /** {@code subject_kind} values: what the case is about. */
    public static final class SubjectKind {
        private SubjectKind() {}

        public static final String BEHAVIOR_PROFILE = "behavior_profile";
        public static final String CLASSIFIER = "classifier";

        /**
         * One (bucket x measure) window state, {@code metric_baseline}. Its own kind rather than
         * {@link #CLASSIFIER} because a metric-drift case is about the population being measured,
         * not the switch that noticed; that population keeps its identity across deploys, re-pins,
         * and the windows that close over it.
         */
        public static final String METRIC_BASELINE = "metric_baseline";

        /**
         * One tool, keyed by its {@code ActionSymbol}. The subject of a tool-error case is
         * deliberately the tool rather than the finding: a rise and a later fall are two causes to
         * explain but one thing to page about, so both collapse onto one live case.
         */
        public static final String TOOL = "tool";

        /**
         * One authored SOP rule, keyed by its slug ({@code conformance_rule.rule_key}). The RULE rather
         * than the finding, for the tool-error reason: a drift, a recovery and a later re-drift are
         * causes to explain, but one obligation to page about.
         */
        public static final String SOP_RULE = "sop_rule";

        /**
         * A call site whose outputs are failing their declared schema — {@code malformed_rate}'s
         * subject. The call site rather than the classifier, for the {@link #TOOL} reason: the schema
         * a call site is failing belongs to it, and a second call site failing its own schema is a
         * second thing to fix, not the same one recurring.
         */
        public static final String CALL_SITE = "call_site";

        /**
         * One secret-detection rule at one call site — {@code armed_window}'s secret-leak subject, the
         * facet {@code ClassifierArming} keys a leak finding on. A leaked AWS key from one call site
         * and a leaked GitHub token from another are two credentials to rotate, so the pattern-and-place
         * pair is the subject, not the classifier that happened to notice either.
         */
        public static final String SECRET_PATTERN = "secret_pattern";
    }

    /** {@code state} values. Muted is live, not closed: see {@code ux_eval_case_live}. */
    public static final class State {
        private State() {}

        public static final String OPEN = "open";
        public static final String RESOLVED = "resolved";
        public static final String MUTED = "muted";
    }

    /** {@code resolution} values: who closed it. */
    public static final class Resolution {
        private Resolution() {}

        /** The detection stopped firing for a full window; closed silently by the reconciler. */
        public static final String RECOVERED = "recovered";

        /** A human closed it, with a required one-line reason. */
        public static final String HUMAN = "human";

        /**
         * A human ruled the shift legitimate and the detector's reference was moved to include it,
         * so the level it fired on is the new baseline.
         *
         * <p>Distinct from {@link #HUMAN}: a case closed as {@code human} leaves the detector's bar
         * exactly where it was, so an unchanged population opens another case tomorrow;
         * {@code absorbed} says the bar moved, and it will not.
         */
        public static final String ABSORBED = "absorbed";
    }

    /**
     * {@code disposition} values: what a person said a resolved frustration or groundedness case turned out to
     * be. Both restart the call site's CUSUM and re-learn its normal rate from the traffic after the resolve;
     * {@link #FALSE_ALARM} also clears the flag on every conversation, or every answer, the case cites.
     */
    public static final class Disposition {
        private Disposition() {}

        /** The agent was changed; the rate after the resolve is the normal to learn. */
        public static final String FIXED = "fixed";

        /** The cited conversations or answers were not what was flagged: they stop counting as failures. */
        public static final String FALSE_ALARM = "false_alarm";
    }

    public boolean isLive() {
        return !State.RESOLVED.equals(state);
    }
}
