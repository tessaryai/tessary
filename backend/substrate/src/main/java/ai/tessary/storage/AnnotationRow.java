// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.jspecify.annotations.Nullable;

/**
 * The {@code annotation} node — a human/llm/consensus judgement over a typed substrate subject,
 * keyed by a free {@code key}. Its surviving job is the classifier's labeled training set
 * (key = classifier_key, {@code passed} = the boolean example label, {@code annotatorKind} = human|llm)
 * plus the "mark wrong" correction on a finding ({@code ofFindingId} + {@code agrees}).
 *
 * <p><b>The other half of this record is gone.</b> It also carried {@code ofVerdictId}, the review of
 * a grader verdict, and the whole review-queue feature that produced those rows. The table survives
 * because {@code classifier/model/ModelService} and {@code classifier/finding/BehaviorTriageSource}
 * still read and write it; only the verdict pointer left, with the table it pointed at.
 *
 * <p>{@code sessionId} is always present; {@code traceId}/{@code spanId} narrow the grain named by
 * {@code subjectKind}.
 */
public record AnnotationRow(
        String id,
        String projectId,
        String subjectKind,
        String sessionId,
        @Nullable String traceId,
        @Nullable String spanId,
        String key,
        @Nullable String annotatorId,
        String annotatorKind,
        String valueType,
        @Nullable Boolean passed,
        @Nullable Double score,
        @Nullable String label,
        @Nullable String textValue,
        /**
         * The finding this correction is about, and now the only anchor there is. It replaced
         * {@code ofVerdictId}, which pointed at a detection verdict: those aged out on the 90-day verdict
         * TTL, so a human's correction outlived the row it was attached to and the training signal was
         * lost by a clock rather than by a decision. A finding lives as long as its cause does.
         */
        @Nullable String ofFindingId,
        @Nullable Boolean agrees,
        @Nullable String comment,
        String createdAt,
        @Nullable String attributes) {

    /** {@code annotator_kind} values. */
    public static final class AnnotatorKind {
        private AnnotatorKind() {}

        public static final String HUMAN = "human";
        public static final String LLM = "llm";
        public static final String CONSENSUS = "consensus";
    }

    /**
     * {@code subject_kind} values (the annotated grain) — the same three-level substrate vocabulary
     * every subject-bearing table uses.
     *
     * <p>The legacy {@code context} / {@code observation} spellings are gone: the surviving
     * rows were migrated onto this vocabulary and {@code ck_annotation_grain} narrowed to it, so accepting
     * them here would only let a caller write a value the database refuses.
     */
    public static final class SubjectKind {
        private SubjectKind() {}

        public static final String TRACE = "trace";

        /** A producer session id, carried on {@code session_id}. */
        public static final String SESSION = "session";

        /** A span, addressed by BOTH {@code trace_id} and {@code span_id} — never one alone. */
        public static final String SPAN = "span";

        /** True for a subject naming one span. */
        public static boolean isSpanGrain(@Nullable String subjectKind) {
            return SPAN.equals(subjectKind);
        }

        /** True for a subject naming a session. */
        public static boolean isSessionGrain(@Nullable String subjectKind) {
            return SESSION.equals(subjectKind);
        }

        /**
         * The grain a subject's id columns imply, in the NEW vocabulary. A span needs its trace: v2 span
         * identity is {@code (project_id, trace_id, id)}, and the {@code 'span'} CHECK branch demands
         * both columns rather than the bare span id the retired {@code 'observation'} branch allowed.
         */
        public static String of(@Nullable String traceId, @Nullable String spanId) {
            if (spanId != null) {
                if (traceId == null) {
                    throw new IllegalArgumentException(
                            "span-grain annotation needs its trace id: span " + spanId + " has none");
                }
                return SPAN;
            }
            return traceId != null ? TRACE : SESSION;
        }
    }

    /** The annotated subject id — the finest non-null of span/trace/session. */
    public String subjectId() {
        if (spanId != null) return spanId;
        if (traceId != null) return traceId;
        return sessionId;
    }

    /**
     * A boolean training-example annotation over a SPAN-grain subject — the successor to a
     * {@code classifier_model_example} row. {@code key} is the classifier_key; {@code passed} is the label;
     * {@code annotatorKind} distinguishes an auto label ({@code llm}) from a user correction ({@code human}).
     *
     * <p>{@code traceId} is required now, not optional: a span subject that does not name its trace
     * cannot be resolved against the span primary key, and the {@code 'span'} branch rejects it.
     */
    public static AnnotationRow example(
            String id,
            String projectId,
            String sessionId,
            String traceId,
            String spanId,
            String key,
            boolean label,
            String annotatorKind,
            String createdAt) {
        return new AnnotationRow(
                id,
                projectId,
                SubjectKind.of(traceId, spanId),
                sessionId,
                traceId,
                spanId,
                key,
                /* annotatorId */ null,
                annotatorKind,
                "boolean",
                /* passed */ label,
                /* score */ null,
                /* label */ null,
                /* textValue */ null,
                /* ofFindingId */ null,
                /* agrees */ null,
                /* comment */ null,
                createdAt,
                /* attributes */ null);
    }
}
