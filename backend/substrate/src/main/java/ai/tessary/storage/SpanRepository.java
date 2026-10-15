// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static ai.tessary.storage.SessionRepository.requireIso;
import static ai.tessary.storage.TraceV2Repository.intOrNull;
import static ai.tessary.storage.TraceV2Repository.longOrNull;
import static ai.tessary.storage.TraceV2Repository.numericOrNull;

import ai.tessary.config.SubstrateProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@code span} table (substrate-model.md §3, §6.2): last-write-wins upserts versioned by {@code event_ts}, and
 * reads through the clustered {@code (project_id, trace_id)} key prefix.
 */
@Repository
public class SpanRepository {

    private static final String COLS = "project_id, trace_id, id, parent_span_id, path::text AS path, depth, "
            + "session_id, user_id, project_version_id, call_site_id, trace_name, kind, name, "
            + "is_logical_root, status, level, error_type, error_message, started_at, ended_at, latency_ms, ttft_ms, "
            + "provided_model_name, model_id, input_tokens, output_tokens, cache_read_tokens, "
            + "cache_write_tokens, reasoning_tokens, total_tokens, input_cost, output_cost, cache_read_cost, "
            + "cache_write_cost, total_cost, cost_source, price_book_version, input_preview, output_preview, "
            + "correlation_state, path_state, event_ts, is_deleted, created_at";

    private final JdbcClient jdbc;

    private final NamedParameterJdbcTemplate named;

    private final JdbcClient resolverJdbc;

    public SpanRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named, SubstrateProperties props) {
        this.jdbc = jdbc;
        this.named = named;
        JdbcTemplate timed = new JdbcTemplate();
        timed.setDataSource(named.getJdbcTemplate().getDataSource());
        timed.setQueryTimeout(props.resolverStatementTimeoutSeconds());
        this.resolverJdbc = JdbcClient.create(timed);
    }

    private static final String UPSERT_SQL = """
                        INSERT INTO span (project_id, trace_id, id, parent_span_id, path, session_id, user_id,
                                          project_version_id, call_site_id, trace_name, kind,
                                          name, is_logical_root, status, level, error_type, error_message,
                                          started_at, ended_at,
                                          latency_ms, ttft_ms, provided_model_name, model_id, input_tokens,
                                          output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens,
                                          input_cost, output_cost, cache_read_cost, cache_write_cost,
                                          cost_source, price_book_version, input_preview, output_preview,
                                          correlation_state, path_state, event_ts, is_deleted)
                        VALUES (:pid, :traceId, :id, :parentSpanId, :path::ltree, :sessionId, :userId,
                                :pvid, :callSiteId, :traceName, :kind, :name, :isLogicalRoot, :status, :level,
                                :errorType, :errorMessage, :startedAt::timestamptz, :endedAt::timestamptz,
                                :latencyMs, :ttftMs,
                                :providedModelName, :modelId, :inputTokens, :outputTokens, :cacheReadTokens,
                                :cacheWriteTokens, :reasoningTokens, :inputCost::numeric, :outputCost::numeric,
                                :cacheReadCost::numeric, :cacheWriteCost::numeric, :costSource,
                                :priceBookVersion, :inputPreview, :outputPreview, :correlationState, :pathState,
                                :eventTs::timestamptz, :isDeleted)
                        ON CONFLICT (project_id, trace_id, id) DO UPDATE
                           SET parent_span_id      = excluded.parent_span_id,
                               session_id          = excluded.session_id,
                               user_id             = excluded.user_id,
                               project_version_id  = excluded.project_version_id,
                               call_site_id        = excluded.call_site_id,
                               trace_name          = excluded.trace_name,
                               kind                = excluded.kind,
                               name                = excluded.name,
                               is_logical_root     = excluded.is_logical_root,
                               status              = excluded.status,
                               level               = excluded.level,
                               error_type          = excluded.error_type,
                               error_message       = excluded.error_message,
                               started_at          = excluded.started_at,
                               ended_at            = excluded.ended_at,
                               latency_ms          = excluded.latency_ms,
                               ttft_ms             = excluded.ttft_ms,
                               provided_model_name = excluded.provided_model_name,
                               model_id            = excluded.model_id,
                               input_tokens        = excluded.input_tokens,
                               output_tokens       = excluded.output_tokens,
                               cache_read_tokens   = excluded.cache_read_tokens,
                               cache_write_tokens  = excluded.cache_write_tokens,
                               reasoning_tokens    = excluded.reasoning_tokens,
                               input_cost          = excluded.input_cost,
                               output_cost         = excluded.output_cost,
                               cache_read_cost     = excluded.cache_read_cost,
                               cache_write_cost    = excluded.cache_write_cost,
                               cost_source         = excluded.cost_source,
                               price_book_version  = excluded.price_book_version,
                               input_preview       = excluded.input_preview,
                               output_preview      = excluded.output_preview,
                               event_ts            = excluded.event_ts,
                               is_deleted          = excluded.is_deleted
                         WHERE excluded.event_ts >= span.event_ts
                        """;

    private static MapSqlParameterSource params(SpanRow row) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        source.addValue("pid", row.projectId());
        source.addValue("traceId", row.traceId());
        source.addValue("id", row.id());
        source.addValue("parentSpanId", row.parentSpanId());
        source.addValue("path", row.path());
        source.addValue("sessionId", row.sessionId());
        source.addValue("userId", row.userId());
        source.addValue("pvid", row.projectVersionId());
        source.addValue("callSiteId", row.callSiteId());
        source.addValue("traceName", row.traceName());
        source.addValue("kind", row.kind());
        source.addValue("name", row.name());
        source.addValue("isLogicalRoot", row.isLogicalRoot());
        source.addValue("status", row.status());
        source.addValue("level", row.level());
        source.addValue("errorType", row.errorType());
        source.addValue("errorMessage", row.errorMessage());
        source.addValue("startedAt", row.startedAt());
        source.addValue("endedAt", row.endedAt());
        source.addValue("latencyMs", row.latencyMs());
        source.addValue("ttftMs", row.ttftMs());
        source.addValue("providedModelName", row.providedModelName());
        source.addValue("modelId", row.modelId());
        source.addValue("inputTokens", row.inputTokens());
        source.addValue("outputTokens", row.outputTokens());
        source.addValue("cacheReadTokens", row.cacheReadTokens());
        source.addValue("cacheWriteTokens", row.cacheWriteTokens());
        source.addValue("reasoningTokens", row.reasoningTokens());
        source.addValue("inputCost", row.inputCost());
        source.addValue("outputCost", row.outputCost());
        source.addValue("cacheReadCost", row.cacheReadCost());
        source.addValue("cacheWriteCost", row.cacheWriteCost());
        source.addValue("costSource", row.costSource());
        source.addValue("priceBookVersion", row.priceBookVersion());
        source.addValue("inputPreview", row.inputPreview());
        source.addValue("outputPreview", row.outputPreview());
        source.addValue("correlationState", row.correlationState());
        source.addValue("pathState", row.pathState());
        source.addValue("eventTs", row.eventTs());
        source.addValue("isDeleted", row.isDeleted());
        return source;
    }

    /**
     * Last-write-wins upsert. The SET list excludes everything the platform derived ({@code path}, the state columns),
     * so a replay never sends a resolved span back to work. The guard is {@code >=}: second-granularity clocks make
     * ties common, and the completed version must win. The caller must re-arm the trace in the same transaction. An
     * arrival also reclaims its retired orphan descendants, since a parent outliving the settle window arrives after
     * they were retired.
     */
    public void upsertAll(List<SpanRow> rows) {
        int[] applied = named.batchUpdate(
                UPSERT_SQL, rows.stream().map(SpanRepository::params).toArray(SqlParameterSource[]::new));
        BatchCounts.requireReal(applied);
        for (int from = 0; from < rows.size(); from += RECLAIM_CHUNK) {
            reclaimOrphansUnder(rows.subList(from, Math.min(rows.size(), from + RECLAIM_CHUNK)));
        }
    }

    /** The OTLP receiver caps a push at 2,000 spans, so the common batch is one statement. */
    private static final int RECLAIM_CHUNK = 1000;

    /**
     * Walks down through orphans only, so resolved subtrees are untouched; each probe hits the partial index
     * {@code ix_span_orphan_parent}.
     */
    private void reclaimOrphansUnder(List<SpanRow> rows) {
        StringBuilder values = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (int i = 0; i < rows.size(); i++) {
            SpanRow row = rows.get(i);
            if (i > 0) values.append(", ");
            values.append("(:p")
                    .append(i)
                    .append(", :t")
                    .append(i)
                    .append(", :s")
                    .append(i)
                    .append(')');
            params.addValue("p" + i, row.projectId());
            params.addValue("t" + i, row.traceId());
            params.addValue("s" + i, row.id());
        }
        named.update(
                "WITH RECURSIVE arrived(project_id, trace_id, id) AS (VALUES " + values + "),"
                        + " reclaim AS ("
                        + "   SELECT c.project_id, c.trace_id, c.id FROM span c"
                        + "     JOIN arrived a ON a.project_id = c.project_id AND a.trace_id = c.trace_id"
                        + "                   AND a.id = c.parent_span_id"
                        + "     JOIN span p ON p.project_id = a.project_id AND p.trace_id = a.trace_id"
                        + "                AND p.id = a.id AND p.path_state <> 'orphan'"
                        + "    WHERE c.path_state = 'orphan'"
                        + "   UNION"
                        + "   SELECT c.project_id, c.trace_id, c.id FROM span c"
                        + "     JOIN reclaim r ON r.project_id = c.project_id AND r.trace_id = c.trace_id"
                        + "                   AND r.id = c.parent_span_id"
                        + "    WHERE c.path_state = 'orphan')"
                        + " UPDATE span s SET path_state = 'pending'"
                        + "   FROM reclaim r"
                        + "  WHERE s.project_id = r.project_id AND s.trace_id = r.trace_id AND s.id = r.id",
                params);
    }

    /**
     * An ltree label accepts only {@code [A-Za-z0-9_]}; one non-conforming producer id would otherwise wedge the
     * resolver forever. Deterministic, so subtree prefixes still match.
     */
    private static final String SPAN_ID_LABEL = "text2ltree(regexp_replace(s.id, '[^A-Za-z0-9_]', '_', 'g'))";

    /**
     * Root is {@code parent_span_id IS NULL}, never "the parent has not arrived", which is what a null {@code path}
     * means.
     */
    public int resolveRootPaths(int limit) {
        return resolverJdbc
                .sql("WITH due AS ("
                        + "  SELECT project_id, trace_id, id FROM span"
                        + "   WHERE path IS NULL AND path_state = 'pending' AND parent_span_id IS NULL"
                        + "   LIMIT :limit)"
                        + " UPDATE span s SET path = " + SPAN_ID_LABEL + ", path_state = 'resolved'"
                        + "  FROM due"
                        + " WHERE s.project_id = due.project_id AND s.trace_id = due.trace_id AND s.id = due.id")
                .param("limit", limit)
                .update();
    }

    /**
     * One level per pass; zero means converged. The parent-is-resolved {@code EXISTS} is in the {@code due} CTE so the
     * {@code LIMIT} only claims resolvable rows; in the outer join alone it re-claimed the same unresolvable head of
     * the index forever.
     */
    public int resolveChildPaths(int limit) {
        return resolverJdbc
                .sql("WITH due AS ("
                        + "  SELECT s.project_id, s.trace_id, s.id FROM span s"
                        + "   WHERE s.path IS NULL AND s.path_state = 'pending' AND s.parent_span_id IS NOT NULL"
                        + "     AND EXISTS (SELECT 1 FROM span p"
                        + "                  WHERE p.project_id = s.project_id AND p.trace_id = s.trace_id"
                        + "                    AND p.id = s.parent_span_id AND p.path IS NOT NULL)"
                        + "   LIMIT :limit)"
                        + " UPDATE span s SET path = p.path || " + SPAN_ID_LABEL + ", path_state = 'resolved'"
                        + "  FROM due, span p"
                        + " WHERE s.project_id = due.project_id AND s.trace_id = due.trace_id AND s.id = due.id"
                        + "   AND p.project_id = s.project_id AND p.trace_id = s.trace_id"
                        + "   AND p.id = s.parent_span_id AND p.path IS NOT NULL")
                .param("limit", limit)
                .update();
    }

    /**
     * Retire spans whose trace has settled with no non-orphan parent row: without a terminal state the permanent
     * residents crowd genuine work out of the index. A pending parent blocks it. A trace an ingest batch holds is
     * skipped ({@code SKIP LOCKED}), or a span could be retired beside a parent that just arrived.
     */
    public int markOrphanPaths(int limit) {
        return resolverJdbc.sql("""
                        WITH due AS (
                            SELECT s.project_id, s.trace_id, s.id
                              FROM span s
                              JOIN trace t ON t.project_id = s.project_id AND t.id = s.trace_id
                             WHERE s.path IS NULL AND s.path_state = 'pending'
                               AND s.parent_span_id IS NOT NULL AND t.is_settled
                               AND NOT EXISTS (
                                   SELECT 1 FROM span p
                                    WHERE p.project_id = s.project_id AND p.trace_id = s.trace_id
                                      AND p.id = s.parent_span_id AND p.path_state <> 'orphan')
                             LIMIT :limit
                               FOR SHARE OF t SKIP LOCKED
                        )
                        UPDATE span s SET path_state = 'orphan'
                          FROM due
                         WHERE s.project_id = due.project_id AND s.trace_id = due.trace_id AND s.id = due.id
                        """).param("limit", limit).update();
    }

    /**
     * The FALLBACK for third-party producers that do not propagate Baggage; {@code ix_span_uncorrelated} keeps it
     * cheap.
     */
    public int backfillCorrelation(int limit) {
        return resolverJdbc.sql("""
                        WITH due AS (
                            SELECT project_id, trace_id, id
                              FROM span
                             WHERE session_id IS NULL AND correlation_state = 'pending'
                             LIMIT :limit
                        )
                        UPDATE span s
                           SET session_id        = t.session_id,
                               user_id           = COALESCE(s.user_id, t.user_id),
                               trace_name        = COALESCE(s.trace_name, t.name),
                               correlation_state = 'done'
                          FROM due, trace t
                         WHERE s.project_id = due.project_id AND s.trace_id = due.trace_id AND s.id = due.id
                           AND t.project_id = s.project_id AND t.id = s.trace_id
                           AND t.session_id IS NOT NULL
                        """).param("limit", limit).update();
    }

    /**
     * Inside the ingest transaction, not left to {@link #backfillCorrelation}: a child of an early-settled trace is
     * already {@code correlation_state = 'none'}, and flipping it back would race {@link #markCorrelationNone}. Writing
     * the span row makes both contend on it.
     */
    public int adoptTraceSession(String projectId, Collection<String> traceIds) {
        if (traceIds.isEmpty()) return 0;
        return jdbc.sql("""
                        WITH due AS (
                            SELECT project_id, trace_id, id
                              FROM span
                             WHERE project_id = :pid AND trace_id IN (:tids) AND session_id IS NULL
                             ORDER BY trace_id, id
                               FOR UPDATE
                        )
                        UPDATE span s
                           SET session_id        = t.session_id,
                               user_id           = COALESCE(s.user_id, t.user_id),
                               trace_name        = COALESCE(s.trace_name, t.name),
                               correlation_state = 'done'
                          FROM due, trace t
                         WHERE s.project_id = due.project_id AND s.trace_id = due.trace_id AND s.id = due.id
                           AND t.project_id = s.project_id AND t.id = s.trace_id
                           AND t.session_id IS NOT NULL
                        """)
                .param("pid", projectId)
                .param("tids", List.copyOf(traceIds))
                .update();
    }

    /**
     * Anonymous traffic is permanent and large; without a terminal state it would crowd every real candidate out of
     * {@code ix_span_uncorrelated}.
     */
    public int markCorrelationNone(int limit) {
        return resolverJdbc.sql("""
                        WITH due AS (
                            SELECT project_id, trace_id, id
                              FROM span
                             WHERE session_id IS NULL AND correlation_state = 'pending'
                             LIMIT :limit
                        )
                        UPDATE span s SET correlation_state = 'none'
                          FROM due, trace t
                         WHERE s.project_id = due.project_id AND s.trace_id = due.trace_id AND s.id = due.id
                           AND t.project_id = s.project_id AND t.id = s.trace_id
                           AND t.session_id IS NULL AND t.is_settled
                        """).param("limit", limit).update();
    }

    public int pendingPathCount() {
        return jdbc.sql("SELECT count(*) FROM span WHERE path IS NULL AND path_state = 'pending'")
                .query(Integer.class)
                .single();
    }

    public int pendingCorrelationCount() {
        return jdbc.sql("SELECT count(*) FROM span WHERE session_id IS NULL AND correlation_state = 'pending'")
                .query(Integer.class)
                .single();
    }

    /**
     * Ordered by event time, not arrival: a batch exporter flushes on END, so arrival is close to reverse causal order.
     */
    public List<SpanRow> listByTrace(String projectId, String traceId) {
        return jdbc.sql("SELECT " + COLS + " FROM span WHERE project_id = :pid AND trace_id = :tid"
                        + " ORDER BY started_at ASC, id ASC")
                .param("pid", projectId)
                .param("tid", traceId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** For display reads: a trace can reach six figures. Ask for {@code cap + 1} to learn whether there was more. */
    public List<SpanRow> listByTrace(String projectId, String traceId, int limit) {
        return jdbc.sql("SELECT " + COLS + " FROM span WHERE project_id = :pid AND trace_id = :tid"
                        + " ORDER BY started_at ASC, id ASC LIMIT :limit")
                .param("pid", projectId)
                .param("tid", traceId)
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** Bounded like {@link #listByTrace(String, String, int)}. */
    public List<SpanRow> listByTraceIds(String projectId, Collection<String> traceIds, int limit) {
        if (traceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLS + " FROM span WHERE project_id = :pid AND trace_id = ANY(:ids)"
                        + " ORDER BY started_at ASC, id ASC LIMIT :limit")
                .param("pid", projectId)
                .param("ids", traceIds.toArray(String[]::new))
                .param("limit", limit)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * Unordered and possibly short: SQL has no order over an id set, and a span retention removed between the reads is
     * dropped.
     */
    public List<SpanRow> listByKeys(String projectId, List<SpanKey> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        var spec = jdbc.sql("SELECT " + COLS + " FROM span WHERE project_id = :pid AND "
                        + SpanKey.tupleIn("trace_id", "id", keys.size()))
                .param("pid", projectId);
        return SpanKey.bind(spec, keys).query((rs, n) -> map(rs)).list();
    }

    /** There is no lookup by bare id: the trace is part of the key. */
    public Optional<SpanRow> findById(String projectId, String traceId, String id) {
        return jdbc.sql("SELECT " + COLS + " FROM span" + " WHERE project_id = :pid AND trace_id = :tid AND id = :id")
                .param("pid", projectId)
                .param("tid", traceId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    private static SpanRow map(ResultSet rs) throws SQLException {
        return new SpanRow(
                rs.getString("project_id"),
                rs.getString("trace_id"),
                rs.getString("id"),
                rs.getString("parent_span_id"),
                rs.getString("path"),
                rs.getString("session_id"),
                rs.getString("user_id"),
                rs.getString("project_version_id"),
                rs.getString("call_site_id"),
                rs.getString("trace_name"),
                rs.getString("kind"),
                rs.getString("name"),
                rs.getBoolean("is_logical_root"),
                rs.getString("status"),
                rs.getString("level"),
                rs.getString("error_type"),
                rs.getString("error_message"),
                requireIso(rs, "started_at"),
                Timestamps.iso(rs, "ended_at"),
                longOrNull(rs, "latency_ms"),
                longOrNull(rs, "ttft_ms"),
                rs.getString("provided_model_name"),
                rs.getString("model_id"),
                longOrNull(rs, "input_tokens"),
                longOrNull(rs, "output_tokens"),
                longOrNull(rs, "cache_read_tokens"),
                longOrNull(rs, "cache_write_tokens"),
                longOrNull(rs, "reasoning_tokens"),
                numericOrNull(rs, "input_cost"),
                numericOrNull(rs, "output_cost"),
                numericOrNull(rs, "cache_read_cost"),
                numericOrNull(rs, "cache_write_cost"),
                rs.getString("cost_source"),
                rs.getString("price_book_version"),
                rs.getString("input_preview"),
                rs.getString("output_preview"),
                rs.getString("correlation_state"),
                rs.getString("path_state"),
                requireIso(rs, "event_ts"),
                rs.getBoolean("is_deleted"),
                intOrNull(rs, "depth"),
                longOrNull(rs, "total_tokens"),
                numericOrNull(rs, "total_cost"),
                Timestamps.iso(rs, "created_at"));
    }
}
