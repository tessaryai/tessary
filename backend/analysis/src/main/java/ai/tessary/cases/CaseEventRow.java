// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import org.jspecify.annotations.Nullable;

/**
 * One {@code eval_case_event} entry; {@link #actor} is null when the system acted. {@link #summary} is never
 * recomputed, so the trail reads correctly after its numbers move on.
 */
public record CaseEventRow(
        String id,
        String kind,
        @Nullable String actor,
        String summary,
        @Nullable String detail,
        String createdAt) {

    /**
     * The CHECK constraint also allows history-only {@code reopened} and {@code recovered}, which older trails carry.
     */
    public static final class Kind {
        private Kind() {}

        public static final String OPENED = "opened";

        /** A later finding on the same cause joined this open case. */
        public static final String RECURRED = "recurred";

        /** A worse value or wider spell while the case was live. */
        public static final String ESCALATED = "escalated";

        public static final String RCA_REQUESTED = "rca_requested";
        public static final String RCA_COMPLETED = "rca_completed";

        public static final String RESOLVED = "resolved";

        /** See {@link CaseRow.Resolution#ABSORBED} for why this differs from resolving. */
        public static final String ABSORBED = "absorbed";
    }
}
