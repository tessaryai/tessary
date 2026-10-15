// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * One row per scored answer, flagged or not: the rate test's trials. Idempotent on
 * {@code (project_id, classifier_id, subject_trace_id, subject_span_id, scorer_version)}, so a rewound sweep writes
 * each answer once.
 */
@Repository
public class GroundednessAssessmentRepository {

    /**
     * @param callSiteId {@code ""} when none; the replay never judges {@code ""} @param unsupported P(unsupported) of
     * the strongest sentence @param spanCreatedAt stands in for event time only when the span and its trace are both
     * gone
     */
    public record Assessment(
            String id,
            String projectId,
            String classifierId,
            @Nullable String sessionId,
            String traceId,
            String spanId,
            String callSiteId,
            double unsupported,
            boolean flagged,
            String scorerVersion,
            String spanCreatedAt) {}

    private final JdbcClient jdbc;

    public GroundednessAssessmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int deleteByClassifier(String projectId, String classifierId) {
        return jdbc.sql("DELETE FROM groundedness_assessment WHERE project_id = :pid AND classifier_id = :cid")
                .param("pid", projectId)
                .param("cid", classifierId)
                .update();
    }

    /**
     * Returns whether it was written. {@code observation_started_at} uses the same expression as a detection's
     * {@code subject_started_at}, so both read on one clock.
     */
    public boolean insert(Assessment a) {
        return jdbc.sql("""
                        INSERT INTO groundedness_assessment (id, project_id, classifier_id, subject_session_id,
                            subject_trace_id, subject_span_id, call_site_id, unsupported, flagged, scorer_version,
                            observation_started_at)
                        VALUES (:id, :pid, :cid, :sessionId, :traceId, :spanId, :callSiteId, :unsupported,
                            :flagged, :scorerVersion,
                            COALESCE((SELECT started_at FROM span
                                       WHERE project_id = :pid AND trace_id = :traceId AND id = :spanId),
                                     (SELECT started_at FROM trace WHERE project_id = :pid AND id = :traceId),
                                     CAST(:fallback AS timestamptz)))
                        ON CONFLICT (project_id, classifier_id, subject_trace_id, subject_span_id, scorer_version)
                        DO NOTHING
                        """)
                        .param("id", a.id())
                        .param("pid", a.projectId())
                        .param("cid", a.classifierId())
                        .param("sessionId", a.sessionId())
                        .param("traceId", a.traceId())
                        .param("spanId", a.spanId())
                        .param("callSiteId", a.callSiteId())
                        .param("unsupported", (float) a.unsupported())
                        .param("flagged", a.flagged())
                        .param("scorerVersion", a.scorerVersion())
                        .param("fallback", a.spanCreatedAt())
                        .update()
                > 0;
    }

    /** Under any scorer; empty until it has. */
    public Optional<Instant> lastScoredAt(String projectId, String classifierId) {
        List<Timestamp> last = jdbc.sql("""
                        SELECT MAX(scored_at) AS last FROM groundedness_assessment
                         WHERE project_id = :pid AND classifier_id = :cid
                        """)
                .param("pid", projectId)
                .param("cid", classifierId)
                .query((rs, n) -> rs.getTimestamp("last"))
                .list();
        return last.get(0) == null ? Optional.empty() : Optional.of(last.get(0).toInstant());
    }
}
