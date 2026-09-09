// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import org.jspecify.annotations.Nullable;

/**
 * One entry in a case's activity trail ({@code eval_case_event}). System and human acts share the
 * table; {@link #actor} is the acting user's email, or null when the reconciler acted.
 *
 * <p>{@link #summary} is written at append time and never recomputed — the trail has to keep reading
 * correctly after the numbers it describes have moved on.
 */
public record CaseEventRow(
        String id,
        String caseId,
        String projectId,
        String kind,
        @Nullable String actor,
        String summary,
        @Nullable String detail,
        String createdAt) {

    /** {@code kind} values — mirrors the {@code eval_case_event} CHECK constraint in the baseline changeset. */
    public static final class Kind {
        private Kind() {}

        public static final String OPENED = "opened";
        public static final String REOPENED = "reopened";

        /** The detection deepened while the case was already live (a worse value, a wider spell). */
        public static final String ESCALATED = "escalated";

        public static final String RCA_REQUESTED = "rca_requested";
        public static final String RCA_COMPLETED = "rca_completed";

        /** The detection stopped firing — the reconciler's silent close. */
        public static final String RECOVERED = "recovered";

        public static final String RESOLVED = "resolved";
        public static final String MUTED = "muted";
        public static final String UNMUTED = "unmuted";

        /** Ruled legitimate, and the detector's reference moved to include it — see
         *  {@link CaseRow.Resolution#ABSORBED} for why that is not the same act as resolving. */
        public static final String ABSORBED = "absorbed";
    }
}
