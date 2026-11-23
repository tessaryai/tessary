// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.storage.Timestamps;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grouped reads over the llm spans linked to a prompt. Only a settled, live trace counts, so a trace still receiving
 * spans cannot be counted twice between two reads. Reads {@code prompt} and {@code prompt_version} directly for the
 * version list, never through the prompt service. The per-name count has no name to seek on, so it reads its window
 * from {@code ix_span_prompt_window}, whose predicate repeats this read's llm and live-span filter word for word. The
 * per-version read uses that index or {@code ix_span_prompt}; both bound it to the window. The prompt's own rows are read
 * only for the versions the usage rows name, so a prompt with many versions costs no more than one with few.
 *
 * <p>The two span reads grow with call volume over the window, so each runs in its own read-only transaction under a
 * transaction-local {@code statement_timeout}; a read cancelled by it is {@code PROMPTS.USAGE_TIMED_OUT}, never a 500
 * and never a connection held while the read runs on. Call them through the Spring proxy: a self-call skips the
 * transaction, and the setting would then bind to nothing.
 */
@Repository
public class PromptUsageRepository {

    /**
     * {@code version} is the producer's string, null when the span named the prompt but no version. The two bounds are
     * the first and last call inside the window, not of all time.
     */
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
            String firstCallInRange,
            String lastCallInRange) {}

    /** {@code latestVersion} is the highest version, 0 only for a prompt whose versions were all deleted under it. */
    public record PromptHead(String id, String name, int latestVersion) {}

    private static final String LINKED_LLM_SPANS = " FROM span s JOIN trace t ON t.project_id = s.project_id"
            + " AND t.id = s.trace_id WHERE s.project_id = :pid AND s.kind = 'llm' AND s.is_deleted IS NOT TRUE"
            + " AND s.started_at >= :since::timestamptz AND t.is_settled AND t.is_deleted IS NOT TRUE";

    private static final String BY_VERSION = "SELECT s.prompt_version, count(*) AS calls,"
            + " count(*) FILTER (WHERE s.status = 'error') AS error_calls,"
            + " percentile_cont(0.5) WITHIN GROUP (ORDER BY s.latency_ms::float8) AS p50_ms,"
            + " percentile_cont(0.95) WITHIN GROUP (ORDER BY s.latency_ms::float8) AS p95_ms,"
            + " sum(s.input_tokens)::bigint AS input_tokens, sum(s.output_tokens)::bigint AS output_tokens,"
            + " sum(s.total_cost) AS cost_usd, count(*) FILTER (WHERE s.cost_source = 'unpriced') AS unpriced_calls,"
            + " min(s.started_at) AS first_call_in_range, max(s.started_at) AS last_call_in_range"
            + LINKED_LLM_SPANS
            + " AND s.prompt_name = :name GROUP BY s.prompt_version ORDER BY s.prompt_version NULLS LAST";

    private static final String CALLS_BY_NAME = "SELECT s.prompt_name, count(*) AS calls"
            + LINKED_LLM_SPANS
            + " AND s.prompt_name IS NOT NULL GROUP BY s.prompt_name ORDER BY s.prompt_name";

    private static final String HEAD = "SELECT p.id, p.name, COALESCE((SELECT max(v.version) FROM prompt_version v"
            + " WHERE v.project_id = p.project_id AND v.prompt_id = p.id), 0) AS latest_version FROM prompt p"
            + " WHERE p.project_id = :pid AND p.id = :id";

    private static final String VERSION_LABELS = "SELECT v.version,"
            + " COALESCE(array_agg(l.label ORDER BY l.label) FILTER (WHERE l.label IS NOT NULL), '{}') AS labels"
            + " FROM prompt_version v LEFT JOIN prompt_label l ON l.version_id = v.id"
            + " WHERE v.project_id = :pid AND v.prompt_id = :promptId AND v.version = ANY(:versions)"
            + " GROUP BY v.version";

    /** Far above the default 7-day read on the window index, which measured about 80 ms on a million linked spans. */
    static final long STATEMENT_TIMEOUT_MS = 5_000;

    /** Postgres's SQLSTATE for a statement cancelled by {@code statement_timeout}. */
    private static final String QUERY_CANCELED = "57014";

    private final JdbcClient jdbc;
    private final long statementTimeoutMs;

    @Autowired
    public PromptUsageRepository(JdbcClient jdbc) {
        this(jdbc, STATEMENT_TIMEOUT_MS);
    }

    PromptUsageRepository(JdbcClient jdbc, long statementTimeoutMs) {
        this.jdbc = jdbc;
        this.statementTimeoutMs = statementTimeoutMs;
    }

    /** Every prompt name with at least one call since {@code since}, in name order. */
    @Transactional(readOnly = true)
    public Map<String, Long> callsByName(String projectId, Instant since) {
        return timed(() -> {
            Map<String, Long> calls = new LinkedHashMap<>();
            for (Map.Entry<String, Long> row : jdbc.sql(CALLS_BY_NAME)
                    .param("pid", projectId)
                    .param("since", since.toString())
                    .query((rs, n) -> Map.entry(rs.getString("prompt_name"), rs.getLong("calls")))
                    .list()) {
                calls.put(row.getKey(), row.getValue());
            }
            return calls;
        });
    }

    @Transactional(readOnly = true)
    public List<VersionUsageRow> byVersion(String projectId, String promptName, Instant since) {
        return timed(() -> jdbc.sql(BY_VERSION)
                .param("pid", projectId)
                .param("name", promptName)
                .param("since", since.toString())
                .query((rs, n) -> versionRow(rs))
                .list());
    }

    /** Runs {@code read} under the timeout; the caller's transaction scopes the setting to it. */
    <T> T timed(Supplier<T> read) {
        jdbc.sql("SELECT set_config('statement_timeout', :ms, true)")
                .param("ms", Long.toString(statementTimeoutMs))
                .query()
                .listOfRows();
        try {
            return read.get();
        } catch (DataAccessException failure) {
            for (Throwable t = failure; t != null; t = t.getCause()) {
                if (t instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
                    throw new TessaryException(PromptsError.USAGE_TIMED_OUT, failure);
                }
            }
            throw failure;
        }
    }

    public Optional<PromptHead> head(String projectId, String promptId) {
        return jdbc.sql(HEAD)
                .param("pid", projectId)
                .param("id", promptId)
                .query((rs, n) -> new PromptHead(rs.getString("id"), rs.getString("name"), rs.getInt("latest_version")))
                .optional();
    }

    /** The stored labels of each of {@code versions} that exists, sorted; a missing version has no key. */
    public Map<Integer, List<String>> storedLabels(String projectId, String promptId, Collection<Integer> versions) {
        Map<Integer, List<String>> labels = new HashMap<>();
        if (versions.isEmpty()) return labels;
        jdbc.sql(VERSION_LABELS)
                .param("pid", projectId)
                .param("promptId", promptId)
                .param("versions", versions.toArray(Integer[]::new))
                .query((rs, n) -> Map.entry(rs.getInt("version"), List.of((String[])
                        rs.getArray("labels").getArray())))
                .list()
                .forEach(e -> labels.put(e.getKey(), e.getValue()));
        return labels;
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
                Objects.requireNonNull(Timestamps.iso(rs, "first_call_in_range")),
                Objects.requireNonNull(Timestamps.iso(rs, "last_call_in_range")));
    }
}
