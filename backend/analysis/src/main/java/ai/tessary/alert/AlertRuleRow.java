// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import org.jspecify.annotations.Nullable;

/**
 * One table for every alert rule, discriminated by {@link #ruleType}. Suppression lives here, never on the classifier:
 * {@link #enabled} is a hard off and {@link #snoozedUntil} a soft mute.
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

    public static final class RuleType {
        private RuleType() {}

        public static final String THRESHOLD = "threshold";

        public static final String DIGEST = "digest";

        public static final String BRIEF = "brief";

        /**
         * One rule per project, and the only type covering the metric and tool-error detectors, which write no per-turn
         * detection a threshold rule could count. Cadence and quiet hours live in {@link #attributes} as an
         * {@link AlertPolicy}; {@link #lastEvaluatedAt} is the anchor, advanced only on delivery.
         */
        public static final String CASE_OPENED = "case_opened";

        /** True for the rule types the {@link AlertWorker} evaluates today. */
        public static boolean isImplemented(String ruleType) {
            return THRESHOLD.equals(ruleType)
                    || DIGEST.equals(ruleType)
                    || BRIEF.equals(ruleType)
                    || CASE_OPENED.equals(ruleType);
        }
    }

    public static final class Basis {
        private Basis() {}

        /** The session-based user proxy. */
        public static final String DISTINCT_USERS = "distinct_users";

        public static final String EVENT_COUNT = "event_count";

        /** Threshold treated as 1. */
        public static final String EVERY_MATCH = "every_match";

        public static boolean isValid(String basis) {
            return DISTINCT_USERS.equals(basis) || EVENT_COUNT.equals(basis) || EVERY_MATCH.equals(basis);
        }
    }
}
