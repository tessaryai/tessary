// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import java.math.BigDecimal;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code frustration_shadow}: the shadow decision model's answer beside each Frustration assessment.
 * Insert only, idempotent on {@code assessment_id}. The backlog is every assessment that still holds
 * its request body (retention nulls it with the span payload) and has no shadow row yet, oldest first,
 * so a turn the shadow never reached before its text aged out is simply never shadowed.
 */
@Repository
public class FrustrationShadowRepository {

    /** An assessment the shadow has not answered yet, with the exact body its model was sent. */
    public record Backlog(
            String assessmentId,
            String projectId,
            String classifierId,
            String traceId,
            String scorerVersion,
            String requestJson,
            boolean referenceFrustrated) {}

    /**
     * One shadow row.
     *
     * @param score the shadow's probability of {@code unhappy_with_assistant}; null when it refused the request
     * @param frustrated {@code score} against the classifier's threshold at scoring time; null with a null score
     * @param referenceFrustrated the assessment's own flag, copied so the pair reads from one row
     * @param responseJson the shadow's answer, or its refusal
     */
    public record ShadowRow(
            String id,
            String assessmentId,
            String projectId,
            String classifierId,
            String traceId,
            String scorerVersion,
            String shadowModel,
            @Nullable BigDecimal score,
            @Nullable Boolean frustrated,
            boolean referenceFrustrated,
            String responseJson,
            @Nullable Integer latencyMs) {}

    /** How the two models have agreed so far, over every project. */
    public record Summary(long scored, long agree, long referenceOnly, long shadowOnly, long refused) {}

    private final JdbcClient jdbc;

    public FrustrationShadowRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The oldest {@code limit} assessments the shadow has not answered and whose request body still exists. */
    public List<Backlog> backlog(int limit) {
        return jdbc.sql("""
                        SELECT a.id, a.project_id, a.classifier_id, a.trace_id, a.scorer_version,
                               a.request::text AS request, a.frustrated
                        FROM frustration_assessment a
                        LEFT JOIN frustration_shadow s ON s.assessment_id = a.id
                        WHERE s.id IS NULL AND a.request IS NOT NULL
                        ORDER BY a.created_at, a.id
                        LIMIT :n
                        """)
                .param("n", limit)
                .query((rs, i) -> new Backlog(
                        rs.getString("id"),
                        rs.getString("project_id"),
                        rs.getString("classifier_id"),
                        rs.getString("trace_id"),
                        rs.getString("scorer_version"),
                        rs.getString("request"),
                        rs.getBoolean("frustrated")))
                .list();
    }

    /** Insert {@code r} unless its assessment already has a shadow row. Returns whether it was written. */
    public boolean insert(ShadowRow r) {
        return jdbc.sql("""
                        INSERT INTO frustration_shadow (id, assessment_id, project_id, classifier_id, trace_id,
                            scorer_version, shadow_model, score, frustrated, reference_frustrated, response, latency_ms)
                        VALUES (:id, :assessmentId, :pid, :cid, :traceId, :scorerVersion, :shadowModel, :score,
                            :frustrated, :referenceFrustrated, CAST(:response AS jsonb), :latencyMs)
                        ON CONFLICT (assessment_id) DO NOTHING
                        """)
                        .param("id", r.id())
                        .param("assessmentId", r.assessmentId())
                        .param("pid", r.projectId())
                        .param("cid", r.classifierId())
                        .param("traceId", r.traceId())
                        .param("scorerVersion", r.scorerVersion())
                        .param("shadowModel", r.shadowModel())
                        .param("score", r.score())
                        .param("frustrated", r.frustrated())
                        .param("referenceFrustrated", r.referenceFrustrated())
                        .param("response", r.responseJson())
                        .param("latencyMs", r.latencyMs())
                        .update()
                > 0;
    }

    /** Agreement so far: rows with a score, split by the two flags; refusals counted apart. */
    public Summary summary() {
        return jdbc.sql("""
                        SELECT COUNT(*) FILTER (WHERE score IS NOT NULL) AS scored,
                               COUNT(*) FILTER (WHERE score IS NOT NULL AND frustrated = reference_frustrated) AS agree,
                               COUNT(*) FILTER (WHERE score IS NOT NULL AND reference_frustrated AND NOT frustrated) AS reference_only,
                               COUNT(*) FILTER (WHERE score IS NOT NULL AND frustrated AND NOT reference_frustrated) AS shadow_only,
                               COUNT(*) FILTER (WHERE score IS NULL) AS refused
                        FROM frustration_shadow
                        """)
                .query((rs, i) -> new Summary(
                        rs.getLong("scored"),
                        rs.getLong("agree"),
                        rs.getLong("reference_only"),
                        rs.getLong("shadow_only"),
                        rs.getLong("refused")))
                .single();
    }
}
