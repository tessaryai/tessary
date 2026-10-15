// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import org.jspecify.annotations.Nullable;

/**
 * One baseline changelog entry: every absorption is a durable row, so an online learner's drift is visible rather than
 * a blind spot.
 */
public record BehaviorBaselineEventRow(
        String id,
        /** Null for a metric-drift baseline event. */
        @Nullable String profileId,
        /** Null for a behaviour-drift event. */
        @Nullable String baselineId,
        String projectId,
        String event,
        @Nullable String workflowKey,
        @Nullable String gramKey,
        String occurredAt,
        @Nullable String detailJson) {

    /** {@code gramKey} carries the finding's {@code cause_key}. */
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
                // The column is NOT NULL and a baseline has no workflow scope, so both classifiers write the same
                // sentinel.
                FindingRow.GLOBAL_WORKFLOW,
                causeKey,
                occurredAt,
                detailJson);
    }

    public static final class Event {
        private Event() {}

        /**
         * The write behind <em>Legitimate, absorb</em>. Only a human can produce it; Layer 2 never mutates the
         * baseline.
         */
        public static final String BASELINE_REPINNED = "baseline_repinned";
    }
}
