// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.storage.Timestamps;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Grouped reads over the llm spans linked to a prompt. Only a settled, live trace counts, so a trace still receiving
 * spans cannot be counted twice between two reads. Reads {@code prompt} and {@code prompt_version} directly for the
 * version list, never through the prompt service. Every statement hits {@code ix_span_prompt}.
 */
@Repository
public class PromptUsageRepository {

    /** {@code version} is the producer's string, null when the span named the prompt but no version. */
    public record VersionUsageRow(
            @Nullable String version,
            long calls,
            long errorCalls,
            @Nullable Double p50Ms,
            @Nullable Double p95Ms,
            @Nullable Long inputTokens,
            @Nullable Long outputTokens,
            @Nullable BigDecimal costUsd,
            long unpricedCalls,
            String firstSeen,
            String lastSeen) {}

    /** {@code versions} ascending; empty only for a prompt whose versions were all deleted under it. */
    public record PromptHead(String id, String name, List<Integer> versions) {}

    private static final String LINKED_LLM_SPANS = " FROM span s JOIN trace t ON t.project_id = s.project_id"
            + " AND t.id = s.trace_id WHERE s.project_id = :pid AND s.kind = 'llm' AND s.is_deleted IS NOT TRUE"
            + " AND s.started_at >= :since::timestamptz AND t.is_settled AND t.is_deleted IS NOT TRUE";

    private static final String BY_VERSION = "SELECT s.prompt_version, count(*) AS calls,"
            + " count(*) FILTER (WHERE s.status = 'error') AS error_calls,"
            + " percentile_cont(0.5) WITHIN GROUP (ORDER BY s.latency_ms::float8) AS p50_ms,"
            + " percentile_cont(0.95) WITHIN GROUP (ORDER BY s.latency_ms::float8) AS p95_ms,"
            + " sum(s.input_tokens)::bigint AS input_tokens, sum(s.output_tokens)::bigint AS output_tokens,"
            + " sum(s.total_cost) AS cost_usd, count(*) FILTER (WHERE s.cost_source = 'unpriced') AS unpriced_calls,"
            + " min(s.started_at) AS first_seen, max(s.started_at) AS last_seen"
            + LINKED_LLM_SPANS
            + " AND s.prompt_name = :name GROUP BY s.prompt_version ORDER BY s.prompt_version NULLS LAST";

    private static final String CALLS_BY_NAME = "SELECT s.prompt_name, count(*) AS calls"
            + LINKED_LLM_SPANS
            + " AND s.prompt_name IS NOT NULL GROUP BY s.prompt_name ORDER BY s.prompt_name";

    private static final String HEAD = "SELECT p.id, p.name,"
            + " COALESCE(array_agg(v.version ORDER BY v.version) FILTER (WHERE v.version IS NOT NULL), '{}')"
            + " AS versions FROM prompt p LEFT JOIN prompt_version v ON v.project_id = p.project_id"
            + " AND v.prompt_id = p.id WHERE p.project_id = :pid AND p.id = :id GROUP BY p.id, p.name";

    private final JdbcClient jdbc;

    public PromptUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every prompt name with at least one call since {@code since}, in name order. */
    public Map<String, Long> callsByName(String projectId, Instant since) {
        Map<String, Long> calls = new LinkedHashMap<>();
        for (Map.Entry<String, Long> row : jdbc.sql(CALLS_BY_NAME)
                .param("pid", projectId)
                .param("since", since.toString())
                .query((rs, n) -> Map.entry(rs.getString("prompt_name"), rs.getLong("calls")))
                .list()) {
            calls.put(row.getKey(), row.getValue());
        }
        return calls;
    }

    public List<VersionUsageRow> byVersion(String projectId, String promptName, Instant since) {
        return jdbc.sql(BY_VERSION)
                .param("pid", projectId)
                .param("name", promptName)
                .param("since", since.toString())
                .query((rs, n) -> versionRow(rs))
                .list();
    }

    public Optional<PromptHead> head(String projectId, String promptId) {
        return jdbc.sql(HEAD)
                .param("pid", projectId)
                .param("id", promptId)
                .query((rs, n) -> new PromptHead(rs.getString("id"), rs.getString("name"), versions(rs)))
                .optional();
    }

    private static VersionUsageRow versionRow(ResultSet rs) throws SQLException {
        return new VersionUsageRow(
                rs.getString("prompt_version"),
                rs.getLong("calls"),
                rs.getLong("error_calls"),
                rs.getObject("p50_ms", Double.class),
                rs.getObject("p95_ms", Double.class),
                rs.getObject("input_tokens", Long.class),
                rs.getObject("output_tokens", Long.class),
                rs.getBigDecimal("cost_usd"),
                rs.getLong("unpriced_calls"),
                // started_at is NOT NULL and a group is never empty, so neither bound can be null.
                Objects.requireNonNull(Timestamps.iso(rs, "first_seen")),
                Objects.requireNonNull(Timestamps.iso(rs, "last_seen")));
    }

    private static List<Integer> versions(ResultSet rs) throws SQLException {
        Array array = rs.getArray("versions");
        return List.of((Integer[]) array.getArray());
    }
}
