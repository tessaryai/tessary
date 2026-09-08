// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.classifier.finding;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One reference from a finding into substrate, at one grain (spec §5).
 *
 * <p>Multiplicity is row count, grain is which column is set, set membership is {@link #role}. Nothing
 * here is a copy: a trace's content is read through the substrate, never duplicated onto the claim.
 *
 * <p>Span identity under substrate v2 is the composite {@code (project_id, trace_id, id)}, so span
 * grain always carries BOTH ids — a bare span id resolves to nothing.
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

    /** {@code finding_evidence.role} values — persisted vocabulary, never renamed. */
    public static final class Role {
        private Role() {}

        /** The one the escalation is pointed at; a classifier writes at most one per finding. */
        public static final String EXEMPLAR = "exemplar";

        /**
         * The shifted / flagged population, ENUMERATED — every row the detector measured on that side,
         * not a selection from it. A reader may sample this set and must say that it did; the writer
         * may not sample it for them, because a claim about a population cannot be audited against
         * evidence whose selection rule is unknown.
         */
        public static final String MEMBER = "member";

        /**
         * The "before" side of a comparison, on the same terms as {@link #MEMBER} — the reference
         * window's rows, enumerated, captured at open and never re-sampled at read time.
         *
         * <p>Absent where a detector's reference is a fitted summary rather than a set of rows (an
         * n-gram profile, a rolling histogram control, a pinned rate pair). Zero baseline refs on such
         * a finding is a fact about what the detector compared against, not a failed write.
         */
        public static final String BASELINE = "baseline";

        /** A further instance of the same failure, so a reader can open more than one. */
        public static final String WITNESS = "witness";

        /** Where a deficit starts concentrating. Descriptive, not a test. */
        public static final String CHANGEPOINT = "changepoint";

        /**
         * The whole vocabulary, in the order {@code ck_finding_evidence_role} declares it.
         *
         * <p>Read by every surface that has to enumerate the roles rather than filter on one — the MCP
         * evidence door's {@code role} enum, and the per-role count maps it answers with. Enumerating
         * matters more than it looks: a role a detector wrote nothing under has to come back as an
         * explicit zero, because {@link #BASELINE} legitimately has none and an absent key would read
         * as a lost write.
         */
        public static final List<String> ALL = List.of(EXEMPLAR, MEMBER, BASELINE, WITNESS, CHANGEPOINT);
    }

    /** The grain this row is at, as the wire word the finding and case surfaces render. */
    public String grain() {
        if (spanId != null) return "span";
        return traceId != null ? "trace" : "session";
    }
}
