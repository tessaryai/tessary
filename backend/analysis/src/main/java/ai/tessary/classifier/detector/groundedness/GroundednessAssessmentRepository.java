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
 * {@code groundedness_assessment}: one row per answer the groundedness model scored, flagged or not,
 * which is what the rate test counts trials from. Insert only, and idempotent on {@code (project_id,
 * classifier_id, subject_trace_id, subject_span_id, scorer_version)}, so a sweep rewound over spans it
 * already scored writes each answer once.
 */
@Repository
public class GroundednessAssessmentRepository {

    /**
     * One assessment row.
     *
     * @param callSiteId the span's call site, or {@code ""} when it has none; the replay never judges
     *     {@code ""}
     * @param unsupported the answer's score, P(unsupported) of its strongest sentence
     * @param flagged whether {@code unsupported} reached the threshold when the answer was scored
     * @param spanCreatedAt the span's ingest time, as the sweep read it; the row's event time comes from
     *     the span itself, and this stands in only when the span and its trace are both gone
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

    /**
     * Insert {@code a} unless this scorer already assessed its span. Returns whether it was written.
     *
     * <p>{@code observation_started_at} is the span's own {@code started_at}, falling back to its
     * trace's, the expression {@code ClassifierDetectionWriteRepository} stamps a detection's
     * {@code subject_started_at} with, so an assessment and the detection beside it read on one clock.
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

    /** When this classifier last scored an answer in the project, under any scorer; empty until it has. */
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
