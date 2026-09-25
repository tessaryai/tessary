// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code frustration_assessment}: one row per user turn sent to the decision model. Insert only, and
 * idempotent on {@code (project_id, classifier_id, trace_id, scorer_version)}, so a page re-sent after a
 * hold writes each turn once. Every read lists its columns; nothing here selects {@code *}, because
 * {@code request} and {@code response} are most of the table's bytes.
 */
@Repository
public class FrustrationAssessmentRepository {

    /**
     * One assessment row.
     *
     * @param conversationId {@code COALESCE(trace.thread_id, trace.session_id)}
     * @param frustrated whether the score exceeded the threshold when the turn was scored
     * @param requestJson the exact body sent; retention nulls it with the turn's span payload
     * @param responseJson the exact body received
     */
    public record Assessment(
            String id,
            String projectId,
            String classifierId,
            String traceId,
            String spanId,
            String conversationId,
            @Nullable String callSiteId,
            Instant turnStartedAt,
            boolean frustrated,
            String scorerVersion,
            String provider,
            String model,
            @Nullable String requestJson,
            String responseJson,
            @Nullable Integer inputTokens,
            @Nullable BigDecimal costUsd,
            @Nullable Integer latencyMs) {}

    /**
     * What a turn's trace says about it.
     *
     * @param conversationId {@code COALESCE(thread_id, session_id)}; null for a trace in no conversation
     */
    public record TurnFacts(@Nullable String conversationId, Instant startedAt) {}

    private final JdbcClient jdbc;

    public FrustrationAssessmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert {@code a} unless this scorer already assessed its turn. Returns whether it was written. */
    public boolean insert(Assessment a) {
        return jdbc.sql("""
                        INSERT INTO frustration_assessment (id, project_id, classifier_id, trace_id, span_id,
                            conversation_id, call_site_id, turn_started_at, frustrated, scorer_version, provider,
                            model, request, response, input_tokens, cost_usd, latency_ms)
                        VALUES (:id, :pid, :cid, :traceId, :spanId, :conversationId, :callSiteId, :startedAt,
                            :frustrated, :scorerVersion, :provider, :model, CAST(:request AS jsonb),
                            CAST(:response AS jsonb), :inputTokens, :costUsd, :latencyMs)
                        ON CONFLICT (project_id, classifier_id, trace_id, scorer_version) DO NOTHING
                        """)
                        .param("id", a.id())
                        .param("pid", a.projectId())
                        .param("cid", a.classifierId())
                        .param("traceId", a.traceId())
                        .param("spanId", a.spanId())
                        .param("conversationId", a.conversationId())
                        .param("callSiteId", a.callSiteId())
                        .param("startedAt", Timestamp.from(a.turnStartedAt()))
                        .param("frustrated", a.frustrated())
                        .param("scorerVersion", a.scorerVersion())
                        .param("provider", a.provider())
                        .param("model", a.model())
                        .param("request", a.requestJson())
                        .param("response", a.responseJson())
                        .param("inputTokens", a.inputTokens())
                        .param("costUsd", a.costUsd())
                        .param("latencyMs", a.latencyMs())
                        .update()
                > 0;
    }

    /** The conversation key and start time of each of {@code traceIds} that still exists. */
    public Map<String, TurnFacts> turnFacts(String projectId, Collection<String> traceIds) {
        if (traceIds.isEmpty()) return Map.of();
        List<Map.Entry<String, TurnFacts>> rows = jdbc.sql("""
                        SELECT id, COALESCE(thread_id, session_id) AS conversation_id, started_at
                          FROM trace
                         WHERE project_id = :pid AND id IN (:traces)
                        """)
                .param("pid", projectId)
                .param("traces", traceIds)
                .query((rs, n) -> Map.entry(
                        rs.getString("id"),
                        new TurnFacts(
                                rs.getString("conversation_id"),
                                rs.getTimestamp("started_at").toInstant())))
                .list();
        Map<String, TurnFacts> out = new HashMap<>();
        for (Map.Entry<String, TurnFacts> e : rows) out.put(e.getKey(), e.getValue());
        return out;
    }
}
