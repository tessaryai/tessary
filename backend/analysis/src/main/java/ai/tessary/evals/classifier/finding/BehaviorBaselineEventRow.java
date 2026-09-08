// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import org.jspecify.annotations.Nullable;

/**
 * One entry in the baseline changelog (PROGRAM.md §4.3). An online learner cannot be stopped from
 * absorbing drift; making every absorption a durable, readable row is what turns that from a blind
 * spot into a changelog.
 */
public record BehaviorBaselineEventRow(
        String id,
        /** The behaviour-drift epoch this happened to, or null for a metric-drift baseline event. */
        @Nullable String profileId,
        /** The metric-drift baseline this happened to, or null for a behaviour-drift event. */
        @Nullable String baselineId,
        String projectId,
        String event,
        @Nullable String workflowKey,
        @Nullable String gramKey,
        String occurredAt,
        @Nullable String detailJson) {

    /** An entry against a behaviour-drift epoch. */
    public static BehaviorBaselineEventRow forProfile(
            String id,
            String profileId,
            String projectId,
            String event,
            @Nullable String workflowKey,
            @Nullable String gramKey,
            String occurredAt,
            @Nullable String detailJson) {
        return new BehaviorBaselineEventRow(
                id, profileId, null, projectId, event, workflowKey, gramKey, occurredAt, detailJson);
    }

    /**
     * An entry against a metric-drift baseline. {@code gramKey} carries the finding's {@code cause_key}
     * — the changelog's "what was this about" column, which for a distribution shift is the same string
     * the Classifiers page renders as the finding's headline.
     */
    public static BehaviorBaselineEventRow forBaseline(
            String id,
            String baselineId,
            String projectId,
            String event,
            @Nullable String causeKey,
            String occurredAt,
            @Nullable String detailJson) {
        return new BehaviorBaselineEventRow(
                id,
                null,
                baselineId,
                projectId,
                event,
                // The identical constant, on the open side of the boundary: a baseline has no workflow
                // scope, and the column is NOT NULL, so both classifiers write the same sentinel.
                FindingRow.GLOBAL_WORKFLOW,
                causeKey,
                occurredAt,
                detailJson);
    }

    /** {@code behavior_baseline_event.event} values. */
    public static final class Event {
        private Event() {}

        public static final String GRAM_GRADUATED = "gram_graduated";
        public static final String GRAM_ALLOWLISTED = "gram_allowlisted";
        public static final String GRAM_BLOCKED = "gram_blocked";
        public static final String PROFILE_ARMED = "profile_armed";
        public static final String PROFILE_STALE = "profile_stale";

        /** A stale epoch re-baselined; counts are inherited, scoring resumes after re-arming. */
        public static final String EPOCH_REOPENED = "epoch_reopened";

        /**
         * A metric-drift baseline's pinned reference was moved onto the current level — the write behind
         * <em>Legitimate — absorb</em>. This is the entry the changelog exists for: the reference is the
         * bar everything is compared against, so moving it silently is how a monitoring system boils a
         * frog. Layer-2 cannot produce this event, only a human pressing the verb can
         * ({@code BehaviorTriageVerdict}: its confidence is read "never as authority to mutate the
         * baseline").
         */
        public static final String BASELINE_REPINNED = "baseline_repinned";
    }
}
