// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.jspecify.annotations.Nullable;

/**
 * The typed substrate subject of a subject-bearing row — at most one of
 * {@code sessionId}/{@code traceId}/{@code spanId}/{@code issueId} is non-null. The polymorphic
 * {@code (subject_kind, subject_id)} pair maps onto it at the writer edge: session/turn/conversation all
 * collapse into {@code sessionId}, and {@code issue} is admitted only by {@code triage_result}/
 * {@code remediation}.
 *
 * <p>Used to keep the writer factories' {@code (kind, id)} call-sites unchanged — they route through
 * {@link #of(String, String)} — while the row records and repositories carry the typed FK columns the
 * schema enforces (with {@code CHECK(num_nonnulls(...) <= 1)}).
 *
 * <p><b>A span subject cannot be fully expressed here</b>, and that is a property of the shape rather
 * than an oversight: v2 span identity is {@code (project_id, trace_id, span_id)}, so a span needs its
 * trace alongside it, while this record holds exactly one id. {@link #of} therefore yields a span with
 * no trace, and every writer that persists one has to supply the trace itself — see
 * {@link FailureModeInstanceRow#clustered}, which refuses the call rather than building a row 0094's
 * {@code ck_fmi_subject} would reject.
 */
public record SubjectRef(
        @Nullable String sessionId,
        @Nullable String traceId,
        @Nullable String spanId,
        @Nullable String issueId) {

    /** The string discriminator kinds, for mapping {@code (kind, id)} at the writer edge. */
    public static final String KIND_SESSION = "session";

    public static final String KIND_TURN = "turn";
    public static final String KIND_CONVERSATION = "conversation";
    public static final String KIND_TRACE = "trace";
    public static final String KIND_SPAN = "span";
    public static final String KIND_ISSUE = "issue";

    public static SubjectRef none() {
        return new SubjectRef(null, null, null, null);
    }

    public static SubjectRef session(String id) {
        return new SubjectRef(id, null, null, null);
    }

    public static SubjectRef trace(String id) {
        return new SubjectRef(null, id, null, null);
    }

    public static SubjectRef span(String id) {
        return new SubjectRef(null, null, id, null);
    }

    public static SubjectRef issue(String id) {
        return new SubjectRef(null, null, null, id);
    }

    /**
     * Map a string {@code (kind, id)} pair to the typed subject. {@code session}/{@code turn}/
     * {@code conversation} → {@code sessionId}, {@code trace} → {@code traceId}, {@code span} →
     * {@code spanId}, {@code issue} → {@code issueId}. A null/blank kind or id, or an unknown kind,
     * yields {@link #none()}.
     */
    public static SubjectRef of(@Nullable String kind, @Nullable String id) {
        if (kind == null || id == null || id.isBlank()) return none();
        return switch (kind) {
            case KIND_SESSION, KIND_TURN, KIND_CONVERSATION -> session(id);
            case KIND_TRACE -> trace(id);
            case KIND_SPAN -> span(id);
            case KIND_ISSUE -> issue(id);
            default -> none();
        };
    }

    /** The single non-null subject id, or null when the subject is unset. */
    public @Nullable String resolvedId() {
        if (sessionId != null) return sessionId;
        if (traceId != null) return traceId;
        if (spanId != null) return spanId;
        return issueId;
    }

    /** The typed kind of the resolved subject ({@code session}/{@code trace}/{@code span}/{@code issue}), or null. */
    public @Nullable String kind() {
        if (sessionId != null) return KIND_SESSION;
        if (traceId != null) return KIND_TRACE;
        if (spanId != null) return KIND_SPAN;
        return issueId != null ? KIND_ISSUE : null;
    }

    /**
     * The typed subject <em>column name</em> for a {@code kind} token — for building
     * grain-filtered SQL over a subject-bearing table. {@code session}/{@code turn}/
     * {@code conversation} → {@code subject_session_id}. Returns null for a null/unknown kind.
     */
    public static @Nullable String columnFor(@Nullable String kind) {
        if (kind == null) return null;
        return switch (kind) {
            case KIND_SESSION, KIND_TURN, KIND_CONVERSATION -> "subject_session_id";
            case KIND_TRACE -> "subject_trace_id";
            case KIND_SPAN -> "subject_span_id";
            case KIND_ISSUE -> "subject_issue_id";
            default -> null;
        };
    }

    /**
     * A SQL {@code CASE} expression deriving the {@code subject_kind} discriminator from the
     * typed FK columns of table alias {@code a} — for all-grain readers. Does not include
     * {@code issue} (only triage/remediation carry it; those readers add it explicitly).
     */
    public static String kindSql(String a) {
        return "CASE WHEN " + a + ".subject_session_id IS NOT NULL THEN 'session'"
                + " WHEN " + a + ".subject_trace_id IS NOT NULL THEN 'trace'"
                + " WHEN " + a + ".subject_span_id IS NOT NULL THEN 'span' END";
    }

    /** A SQL {@code COALESCE} deriving the {@code subject_id} from the typed FK columns of alias {@code a}. */
    public static String idSql(String a) {
        return "COALESCE(" + a + ".subject_session_id, " + a + ".subject_trace_id, " + a + ".subject_span_id)";
    }
}
