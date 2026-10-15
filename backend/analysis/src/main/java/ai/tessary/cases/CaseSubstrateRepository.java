// SPDX-License-Identifier: Apache-2.0
package ai.tessary.cases;

import ai.tessary.classifier.substrate.BehaviorSubstrateRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Hydrates the trace ids classifiers recorded as {@code finding_evidence}, and counts coverage for Triage's all-clear
 * state. Nothing here chooses which traces a case shows. Time is {@code trace.started_at}, event time.
 */
@Repository
public class CaseSubstrateRepository {

    private static final String SELECT = """
            SELECT tr.id, tr.name, tr.latency_ms, tr.total_cost, tr.started_at AS at
            FROM trace tr
            WHERE tr.project_id = :pid AND tr.is_deleted IS NOT TRUE
            """;

    private final JdbcClient jdbc;

    public CaseSubstrateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One trace, as a case renders it: enough to tell two of them apart without opening either. */
    public record Exemplar(
            String traceId,
            @Nullable String name,
            @Nullable Long durationMs,
            @Nullable Double costUsd,
            @Nullable String startedAt) {}

    /**
     * In the order given (exemplar, then witnesses). An id with no row is dropped: retention may have aged it out, and
     * a link that 404s is worse than one fewer example.
     */
    public List<Exemplar> byIds(String projectId, List<String> traceIds) {
        if (traceIds.isEmpty()) return List.of();
        Map<String, Exemplar> found = new HashMap<>();
        for (Exemplar e : jdbc.sql(SELECT + " AND tr.id IN (:ids)")
                .param("pid", projectId)
                .param("ids", traceIds)
                .query(CaseSubstrateRepository::map)
                .list()) {
            found.putIfAbsent(e.traceId(), e);
        }
        List<Exemplar> ordered = new ArrayList<>();
        for (String id : traceIds) {
            Exemplar hit = found.get(id);
            if (hit != null) ordered.add(hit);
        }
        return ordered;
    }

    /**
     * Separates coverage from a broken exporter: "nothing needs you" over zero traces must not read as an all-clear.
     */
    public long countTracesSince(String projectId, String since) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM trace tr
                WHERE tr.project_id = :pid AND tr.is_deleted IS NOT TRUE
                  AND tr.started_at >= :since::timestamptz""")
                .param("pid", projectId)
                .param("since", since)
                .query(Long.class)
                .single();
    }

    /**
     * Unbounded on purpose: {@code ix_trace_project_live} (migration 0021) is a partial index matching exactly this
     * WHERE clause, making it index-only. Widen the predicate and the index silently stops covering it.
     */
    public long countTraces(String projectId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM trace tr
                WHERE tr.project_id = :pid AND tr.is_deleted IS NOT TRUE""").param("pid", projectId).query(Long.class).single();
    }

    /**
     * Counted from spans rather than the pipeline, since call sites can arrive tagged on spans without a bundle.
     * {@code __unattributed__} is excluded.
     */
    public long countCallSitesSince(String projectId, String since) {
        return jdbc.sql("""
                SELECT COUNT(DISTINCT o.call_site_id) FROM span o
                WHERE o.project_id = :pid AND o.call_site_id IS NOT NULL
                  AND o.call_site_id <> :unattributed
                  AND o.started_at >= :since::timestamptz""")
                .param("pid", projectId)
                .param("unattributed", BehaviorSubstrateRepository.UNATTRIBUTED)
                .param("since", since)
                .query(Long.class)
                .single();
    }

    private static Exemplar map(ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        String name = rs.getString("name");
        long latency = rs.getLong("latency_ms");
        Long durationMs = rs.wasNull() ? null : latency; // must follow its own read — wasNull is per-column
        BigDecimal cost = rs.getBigDecimal("total_cost");
        OffsetDateTime at = rs.getObject("at", OffsetDateTime.class);
        return new Exemplar(
                id,
                name,
                durationMs,
                cost == null ? null : cost.doubleValue(),
                at == null ? null : at.toInstant().toString());
    }
}
