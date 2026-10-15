// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One reference from a finding into substrate; grain is which column is set, membership is {@link #role}. Nothing is
 * copied. A span always carries both trace and span ids, since a bare span id resolves to nothing.
 */
public record FindingEvidenceRow(
        String id,
        String projectId,
        String findingId,
        @Nullable String sessionId,
        @Nullable String traceId,
        @Nullable String spanId,
        String role,
        @Nullable Integer rank,
        String createdAt) {

    /** Persisted vocabulary, never renamed. */
    public static final class Role {
        private Role() {}

        /** At most one per finding. */
        public static final String EXEMPLAR = "exemplar";

        /**
         * ENUMERATED: every row measured on that side. A reader may sample it and must say so; the writer may not,
         * because a claim cannot be audited against an unknown selection rule.
         */
        public static final String MEMBER = "member";

        /**
         * Enumerated like {@link #MEMBER}, captured at open. Absent where the reference is a fitted summary, which is a
         * fact, not a failed write.
         */
        public static final String BASELINE = "baseline";

        /** A further instance of the same failure, so a reader can open more than one. */
        public static final String WITNESS = "witness";

        /** Where a deficit starts concentrating. Descriptive, not a test. */
        public static final String CHANGEPOINT = "changepoint";

        /**
         * In {@code ck_finding_evidence_role} order. Surfaces that enumerate must report an explicit zero, since
         * {@link #BASELINE} legitimately has none and an absent key reads as a lost write.
         */
        public static final List<String> ALL = List.of(EXEMPLAR, MEMBER, BASELINE, WITNESS, CHANGEPOINT);
    }
}
