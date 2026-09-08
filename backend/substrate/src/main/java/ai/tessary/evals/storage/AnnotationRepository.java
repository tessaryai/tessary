// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JdbcClient repository for the {@code annotation} node. Upsert-on-current (one row per
 * {@code (annotator_kind, key, subject)} via {@code ux_annotation_current}) plus subject and key reads.
 * There is no verdict read: {@code findByVerdict} went with the {@code of_verdict_id} column in Track A.
 * The classifier training set lives here as {@code annotator_kind}-tagged boolean examples keyed by
 * classifier_key; a "mark wrong" correction as an {@code of_finding_id + agrees} row.
 *
 * <p><b>Subjects are producer keys now.</b> {@code session_id} holds a producer session id (or, for a
 * trace with no session, its producer trace id — the column is NOT NULL and is dropped in the teardown),
 * {@code trace_id} a producer trace id, and {@code span_id} a producer span id that is only
 * meaningful ALONGSIDE its trace. The conflict key on the upsert already carried all three columns, so
 * it needed no change; {@link #findBySubject} did, and gained the trace and the project scoping it was
 * missing.
 */
@Repository
public class AnnotationRepository {

    private static final String COLS = "id, project_id, subject_kind, session_id, trace_id, span_id, "
            + "key, annotator_id, annotator_kind, value_type, passed, score, label, text_value, "
            + "of_finding_id, "
            + "agrees, comment, created_at, attributes";

    private final JdbcClient jdbc;

    public AnnotationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AnnotationRow row) {
        bindRow(insertSql(""), row).update();
    }

    /**
     * Upsert the CURRENT annotation for {@code (annotator_kind, annotator_id, key, subject)} — a
     * re-annotation by the same annotator supersedes its own prior row (a human correction and an llm
     * auto-label coexist as distinct rows). Another annotator's is never lost.
     *
     * <p>The annotator id is part of the key deliberately. Without it every human reviewing the same
     * subject collided on one row and the last write won, which made
     * {@code annotation_queue.reviewers_per_item} unimplementable and destroyed exactly the
     * disagreement data inter-rater agreement is computed from (migration 0039).
     */
    public void upsert(AnnotationRow row) {
        bindRow(
                        insertSql(
                                " ON CONFLICT (annotator_kind, annotator_id, key, subject_kind, session_id, trace_id, span_id) "
                                        + "DO UPDATE SET passed = EXCLUDED.passed, score = EXCLUDED.score, "
                                        + "label = EXCLUDED.label, text_value = EXCLUDED.text_value, "
                                        + "agrees = EXCLUDED.agrees, comment = EXCLUDED.comment, "
                                        + "annotator_id = EXCLUDED.annotator_id, attributes = EXCLUDED.attributes, "
                                        + "created_at = EXCLUDED.created_at"),
                        row)
                .update();
    }

    private JdbcClient.StatementSpec insertSql(String conflict) {
        return jdbc.sql("INSERT INTO annotation (" + COLS + ") VALUES (:id, :pid, :subjectKind, :sessionId, "
                + ":traceId, :spanId, :key, :annotatorId, :annotatorKind, :valueType, :passed, :score, "
                + ":label, :textValue, :ofFindingId, :agrees, :comment, :createdAt::timestamptz, "
                + ":attributes::jsonb)"
                + conflict);
    }

    private JdbcClient.StatementSpec bindRow(JdbcClient.StatementSpec spec, AnnotationRow row) {
        return spec.param("id", row.id())
                .param("pid", row.projectId())
                .param("subjectKind", row.subjectKind())
                .param("sessionId", row.sessionId())
                .param("traceId", row.traceId())
                .param("spanId", row.spanId())
                .param("key", row.key())
                .param("annotatorId", row.annotatorId())
                .param("annotatorKind", row.annotatorKind())
                .param("valueType", row.valueType())
                .param("passed", row.passed())
                .param("score", row.score())
                .param("label", row.label())
                .param("textValue", row.textValue())
                .param("ofFindingId", row.ofFindingId())
                .param("agrees", row.agrees())
                .param("comment", row.comment())
                .param("createdAt", row.createdAt())
                .param("attributes", row.attributes());
    }

    /** Every current annotation for one {@code key} in a project, one per subject (human preferred over llm). */
    public List<AnnotationRow> currentByKey(String projectId, String key) {
        return jdbc.sql("SELECT DISTINCT ON (subject_kind, session_id, trace_id, span_id) " + COLS
                        + " FROM annotation WHERE project_id = :pid AND key = :key "
                        + "ORDER BY subject_kind, session_id, trace_id, span_id, "
                        // human > consensus > llm, then newest, so a human correction wins per subject.
                        + "(annotator_kind = 'human') DESC, (annotator_kind = 'consensus') DESC, created_at DESC")
                .param("pid", projectId)
                .param("key", key)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * Every annotation for one {@code key} in a project (ALL rows, not one-per-subject) — the raw input
     * to consensus derivation. Ordered by subject grain then newest-first so callers can group by grain
     * and treat the first row of a group as the most recent.
     */
    public List<AnnotationRow> findByProjectAndKey(String projectId, String key) {
        return jdbc.sql("SELECT " + COLS + " FROM annotation WHERE project_id = :pid AND key = :key "
                        + "ORDER BY subject_kind, session_id, trace_id, span_id, created_at DESC")
                .param("pid", projectId)
                .param("key", key)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * All annotations on one typed subject, newest first.
     *
     * <p><b>A span subject is a PAIR, and it is project-scoped.</b> The old signature took a bare subject
     * id and, for the span grain, matched {@code span_id = :id} with neither project nor trace
     * scoping — survivable only because a surrogate observation id was globally unique. A producer span
     * id is unique only within its trace, so that same query in v2 would return another trace's
     * annotations and, worse, another tenant's. {@code traceId} is therefore required for the span grain
     * and ignored for the others; every caller already holds it, because holding it is how they found
     * the subject at all.
     *
     * <p>There is one vocabulary — {@code session}/{@code trace}/{@code span} — since 0094 migrated the
     * rows that said the other one; see {@link AnnotationRow.SubjectKind}.
     */
    public List<AnnotationRow> findBySubject(
            String projectId, String subjectKind, @Nullable String traceId, String subjectId) {
        boolean spanGrain = AnnotationRow.SubjectKind.isSpanGrain(subjectKind);
        if (spanGrain && traceId == null) {
            throw new IllegalArgumentException(
                    "span-grain annotation lookup needs its trace id: span " + subjectId + " has none");
        }
        String predicate;
        if (spanGrain) {
            predicate = "trace_id = :traceId AND span_id = :id";
        } else if (AnnotationRow.SubjectKind.TRACE.equals(subjectKind)) {
            predicate = "trace_id = :id";
        } else {
            predicate = "session_id = :id";
        }
        var spec = jdbc.sql("SELECT " + COLS + " FROM annotation WHERE project_id = :pid AND " + predicate
                        + " ORDER BY created_at DESC")
                .param("pid", projectId)
                .param("id", subjectId);
        if (spanGrain) spec = spec.param("traceId", traceId);
        return spec.query((rs, n) -> map(rs)).list();
    }

    private static AnnotationRow map(ResultSet rs) throws SQLException {
        return new AnnotationRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("subject_kind"),
                rs.getString("session_id"),
                rs.getString("trace_id"),
                rs.getString("span_id"),
                rs.getString("key"),
                rs.getString("annotator_id"),
                rs.getString("annotator_kind"),
                rs.getString("value_type"),
                (Boolean) rs.getObject("passed"),
                (Double) rs.getObject("score"),
                rs.getString("label"),
                rs.getString("text_value"),
                rs.getString("of_finding_id"),
                (Boolean) rs.getObject("agrees"),
                rs.getString("comment"),
                Timestamps.iso(rs, "created_at"),
                rs.getString("attributes"));
    }
}
