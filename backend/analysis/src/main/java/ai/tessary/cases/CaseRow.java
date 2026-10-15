// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import org.jspecify.annotations.Nullable;

/**
 * Cause-neutral: {@link #detector} is the only field that says what noticed. Identity is the same subject key
 * {@code rca_report} carries; {@code ux_eval_case_live} enforces one non-resolved case per key. Detectors don't share a
 * bar, so ordering uses {@link #severity} and {@code basis} carries each detector's own account.
 */
public record CaseRow(
        String id,
        long seq,
        String detector,
        String subjectKind,
        String subjectId,
        String subjectLabel,
        @Nullable String callSiteId,
        String metric,
        /** Zero for an archived pre-{@code case_id} row. */
        long findingCount,
        /**
         * The newest of this case's findings, which its header, ruling and RCA lane read. Null only for an archived
         * case from before {@code finding.case_id}.
         */
        @Nullable String latestFindingId,
        String state,
        /** A locked case never gets a new finding joined to it; the cause's next positive opens a fresh case. */
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
        @Nullable String disposition) {

    public String reference() {
        return "C-" + seq;
    }

    /** New detectors add a constant here and a {@link CaseSource} implementation; nothing else in the slice changes. */
    public static final class Detector {
        private Detector() {}

        public static final String CLASSIFIER = "classifier";

        /** Findings stream with no alert budget, so only a triage-positive or human-ruled deviation reaches Triage. */
        public static final String METRIC_DRIFT = "metric_drift";

        /** Same gate as {@link #METRIC_DRIFT}. */
        public static final String TOOL_ERROR = "tool_error";

        /**
         * Ruled positive at arming when high confidence: a credential in stored output is a fact to rotate, not a claim
         * to audit.
         */
        public static final String SECRET_LEAK = "secret_leak";

        /** Same gate as {@link #METRIC_DRIFT}. */
        public static final String MALFORMED_OUTPUT = "malformed_output";

        /** No triage gate: each spell's finding is ruled positive when filed. */
        public static final String FRUSTRATION = "frustration";

        /**
         * A call site whose share of traces with a flagged answer rose above the rate it learned. Same gate
         * as {@link #MALFORMED_OUTPUT}: triage rules on the finding, and only a ruling of {@code positive},
         * or a human pressing <em>Real deviation</em>, reaches Triage. See {@link RateCaseSource}.
         */
        public static final String GROUNDEDNESS = "groundedness";
    }

    public static final class SubjectKind {
        private SubjectKind() {}

        public static final String CLASSIFIER = "classifier";

        /**
         * Its own kind: a metric-drift case is about the measured population, which keeps its identity across deploys
         * and re-pins.
         */
        public static final String METRIC_BASELINE = "metric_baseline";

        /** The tool, not the finding: a rise and a later fall are two causes but one thing to page about. */
        public static final String TOOL = "tool";

        /** The call site, not the classifier: a second call site failing its own schema is a second thing to fix. */
        public static final String CALL_SITE = "call_site";

        /**
         * Pattern and place: leaked keys of different kinds at different call sites are separate credentials to rotate.
         */
        public static final String SECRET_PATTERN = "secret_pattern";
    }

    public static final class State {
        private State() {}

        public static final String OPEN = "open";
        public static final String RESOLVED = "resolved";
    }

    public static final class Resolution {
        private Resolution() {}

        public static final String HUMAN = "human";

        /**
         * Unlike {@link #HUMAN}, which leaves the bar where it was, {@code absorbed} says the reference moved, so the
         * same population will not reopen a case.
         */
        public static final String ABSORBED = "absorbed";
    }

    /**
     * Both values restart the call site's CUSUM and re-learn from traffic after the resolve; {@link #FALSE_ALARM} also
     * clears the flags the case cites.
     */
    public static final class Disposition {
        private Disposition() {}

        public static final String FALSE_ALARM = "false_alarm";
    }

    public boolean isLive() {
        return !State.RESOLVED.equals(state);
    }
}
