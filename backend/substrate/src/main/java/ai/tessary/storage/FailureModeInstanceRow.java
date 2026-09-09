// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.jspecify.annotations.Nullable;

/**
 * One member of a {@link FailureModeRow}. A typed
 * substrate subject (session/trace/span, same vocabulary as verdict/annotation) clustered into a
 * failure mode, carrying the {@code verdictId} of the detection it was clustered from (a
 * {@code source='automatic'} verdict) and how it was attributed
 * ({@code source}: clustered|human|grader). Unique per {@code (failure_mode, subject grain)}.
 */
public record FailureModeInstanceRow(
        String id,
        String projectId,
        String failureModeId,
        String subjectKind,
        @Nullable String subjectSessionId,
        @Nullable String subjectTraceId,
        @Nullable String subjectSpanId,
        @Nullable String verdictId,
        String source,
        @Nullable String confidence,
        @Nullable String occurredAt,
        String createdAt,
        @Nullable String attributes) {

    /** {@code source} values (how the subject was attributed to the failure mode). */
    public static final class Source {
        private Source() {}

        public static final String CLUSTERED = "clustered";
        public static final String HUMAN = "human";
        public static final String GRADER = "grader";
    }

    /** The clustered subject id — the finest non-null of span/trace/session. */
    public String subjectId() {
        if (subjectSpanId != null) return subjectSpanId;
        if (subjectTraceId != null) return subjectTraceId;
        return subjectSessionId != null ? subjectSessionId : "";
    }

    /**
     * Build a {@code clustered} instance from a polymorphic {@code (subjectKind, subjectId)} — the finest
     * grain routes to the matching typed column (session/trace/span) via {@link SubjectRef}.
     *
     * <p>A SPAN subject is rejected: 0094's {@code ck_fmi_subject} requires the span's trace alongside it
     * (span identity is the pair), and a {@code (kind, id)} pair cannot carry one. Failing here names the
     * missing half; letting it through would surface as a constraint violation on insert.
     */
    public static FailureModeInstanceRow clustered(
            String id,
            String projectId,
            String failureModeId,
            String subjectKind,
            String subjectId,
            @Nullable String verdictId,
            @Nullable String occurredAt,
            String createdAt) {
        SubjectRef s = SubjectRef.of(subjectKind, subjectId);
        if (s.spanId() != null) {
            throw new IllegalArgumentException(
                    "span-grain failure-mode instance needs its trace id: span " + subjectId + " has none");
        }
        String kind = s.traceId() != null ? SubjectKind.TRACE : SubjectKind.SESSION;
        return new FailureModeInstanceRow(
                id,
                projectId,
                failureModeId,
                kind,
                s.sessionId(),
                s.traceId(),
                s.spanId(),
                verdictId,
                Source.CLUSTERED,
                null,
                occurredAt,
                createdAt,
                null);
    }

    /** {@code subject_kind} values (matches the failure_mode_instance CHECK). */
    public static final class SubjectKind {
        private SubjectKind() {}

        public static final String SESSION = "session";
        public static final String TRACE = "trace";
        public static final String SPAN = "span";
    }
}
