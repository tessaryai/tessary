// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import org.jspecify.annotations.Nullable;

/**
 * The one unified alert-rule config the {@link AlertWorker} evaluates on a heartbeat. Both
 * per-classifier threshold rules and per-project roll-up schedules live in a single table
 * discriminated by {@link #ruleType}:
 *
 * <ul>
 *   <li>{@link RuleType#THRESHOLD} — a per-classifier threshold breach ({@link #classifierId}, {@link #basis},
 *       {@link #threshold}, {@link #windowSeconds}). one rule per classifier ({@code UNIQUE(classifier_id)}).
 *   <li>{@link RuleType#DIGEST} / {@link RuleType#BRIEF} — a per-project scheduled roll-up
 *       ({@link #digestCron} / {@link #briefCron}). At most one of each per project.
 *   <li>{@link RuleType#ANOMALY}, {@link RuleType#TRACE}, {@link RuleType#LOG}, {@link RuleType#EXCEPTION}
 *       — reserved rule types the schema accepts for a later increment; not yet evaluated.
 * </ul>
 *
 * <p>The two suppression controls live HERE, never on the classifier: {@link #enabled} is a hard off and
 * {@link #snoozedUntil} is a soft mute. {@link #lastEvaluatedAt} is the threshold claim-order key;
 * {@link #lastDigestAt}/{@link #lastBriefAt} are the roll-up cron anchors.
 */
public record AlertRuleRow(
        String id,
        String projectId,
        String ruleType,
        String name,
        @Nullable String classifierId,
        @Nullable String target,
        @Nullable String basis,
        @Nullable Integer threshold,
        @Nullable Integer windowSeconds,
        @Nullable String groupBy,
        @Nullable Integer minSamples,
        @Nullable String severity,
        @Nullable String digestCron,
        @Nullable String briefCron,
        boolean enabled,
        @Nullable String snoozedUntil,
        @Nullable String lastEvaluatedAt,
        @Nullable String lastDigestAt,
        @Nullable String lastBriefAt,
        String attributes,
        String createdAt,
        String updatedAt) {

    /** The grain a rule is expressed at — the {@code rule_type} discriminator. */
    public static final class RuleType {
        private RuleType() {}

        /** A per-classifier threshold breach over a rolling window. */
        public static final String THRESHOLD = "threshold";

        /** The daily project-wide roll-up. */
        public static final String DIGEST = "digest";

        /** The scheduled recurring project-wide report. */
        public static final String BRIEF = "brief";

        /**
         * <b>A case opened.</b> One rule per project, and the only rule type that covers all three launch
         * detectors — because the case is the only unit all three produce.
         *
         * <p>A {@link #THRESHOLD} rule counts a classifier's DETECTIONS, and a detection is a
         * {@code verdict} row with {@code source='automatic'}. None of {@code duration_drift},
         * {@code cost_drift} or {@code tool_error} writes one: the two metric detectors say a
         * <em>population</em> moved, which is not a statement about any individual turn, and tool error
         * recomputes a rate from an hourly aggregate. Pointing a threshold rule at any of them counts zero
         * forever. That is not a gap in alerting — it is the unit not existing for those detectors.
         *
         * <p>What does exist, for all three and by construction rather than by coincidence, is the case:
         * they pass the same triage gate, and a case is what a human is supposed to act on. So the
         * coverage requirement is met by one rule rather than by a per-detector mechanism that would need
         * a fourth entry the next time a detector lands.
         *
         * <p>Unlike the roll-ups, this rule's schedule is not a cron. Its cadence and quiet hours live in
         * {@link #attributes} as an {@link AlertPolicy}, and {@link #lastEvaluatedAt} is its anchor: every
         * case opened since the anchor fires, and the anchor advances only when a tick actually delivers.
         */
        public static final String CASE_OPENED = "case_opened";

        /** Reserved: a per-classifier statistical anomaly (baseline vs. window). Not yet evaluated. */
        public static final String ANOMALY = "anomaly";

        /** Reserved: a trace-shape rule. Not yet evaluated. */
        public static final String TRACE = "trace";

        /** Reserved: a log-pattern rule. Not yet evaluated. */
        public static final String LOG = "log";

        /** Reserved: an exception-rate rule. Not yet evaluated. */
        public static final String EXCEPTION = "exception";

        /** True for the rule types the {@link AlertWorker} evaluates today. */
        public static boolean isImplemented(String ruleType) {
            return THRESHOLD.equals(ruleType)
                    || DIGEST.equals(ruleType)
                    || BRIEF.equals(ruleType)
                    || CASE_OPENED.equals(ruleType);
        }

        /** True for the per-project scheduled roll-up grains. */
        public static boolean isRollup(String ruleType) {
            return DIGEST.equals(ruleType) || BRIEF.equals(ruleType);
        }
    }

    /** How a threshold window's matches are counted before comparing to the threshold. */
    public static final class Basis {
        private Basis() {}

        /** {@code COUNT(DISTINCT session)} of matched observations — the session-based user proxy. */
        public static final String DISTINCT_USERS = "distinct_users";

        /** {@code COUNT(*)} of matched detections in the window. */
        public static final String EVENT_COUNT = "event_count";

        /** Fire whenever the window holds at least one qualifying match (threshold treated as 1). */
        public static final String EVERY_MATCH = "every_match";

        public static boolean isValid(String basis) {
            return DISTINCT_USERS.equals(basis) || EVENT_COUNT.equals(basis) || EVERY_MATCH.equals(basis);
        }
    }
}
