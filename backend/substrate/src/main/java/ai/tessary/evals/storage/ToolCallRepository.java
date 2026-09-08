// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JdbcClient repository for the first-class {@code tool_call} table — a tool invocation hung off the
 * span that made it. Append-only insert plus a trace-scoped read.
 *
 * <p>Idempotent insert: {@code ON CONFLICT DO NOTHING} against the primary key, which is derived from
 * {@code (project, trace, span, seq)} ({@code SideTableIds}), makes the write path's at-least-once
 * replays first-write-wins no-ops. It used to lean on the natural key
 * {@code (observation_id, source_external_id)}, which stopped deduping the moment {@code observation_id}
 * went null and is gone with that column. jsonb/timestamptz columns carry {@code String} bound with explicit casts;
 * timestamps read back as ISO-8601 via {@link Timestamps}.
 */
@Repository
public class ToolCallRepository {

    private static final String COLS = "id, project_id, name, tool_call_id, tool_type, "
            + "scope, arguments, arguments_raw, result, error_type, error_message, is_error, retries, "
            + "latency_ms, source_external_id, event_ts, is_deleted, attributes, started_at, created_at, "
            + "trace_id, span_id";

    private final JdbcClient jdbc;

    private final NamedParameterJdbcTemplate named;

    public ToolCallRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    private static final String INSERT_SQL = """
            INSERT INTO tool_call (id, project_id, name, tool_call_id, tool_type, scope,
                                   arguments, arguments_raw, result, error_type, error_message, is_error,
                                   retries, latency_ms, source_external_id, event_ts, is_deleted, attributes,
                                   started_at, created_at, trace_id, span_id)
            VALUES (:id, :pid, :name, :toolCallId, :toolType, :scope, :arguments::jsonb,
                    :argumentsRaw, :result::jsonb, :errorType, :errorMessage, :isError, :retries,
                    :latencyMs, :sourceExternalId, :eventTs::timestamptz, :isDeleted, :attributes::jsonb,
                    :startedAt::timestamptz, :createdAt::timestamptz, :traceId, :spanId)
            ON CONFLICT DO NOTHING
            """;

    private static MapSqlParameterSource params(ToolCallRow row) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        source.addValue("id", row.id());
        source.addValue("pid", row.projectId());
        source.addValue("name", row.name());
        source.addValue("toolCallId", row.toolCallId());
        source.addValue("toolType", row.toolType());
        source.addValue("scope", row.scope());
        source.addValue("arguments", row.arguments());
        source.addValue("argumentsRaw", row.argumentsRaw());
        source.addValue("result", row.result());
        source.addValue("errorType", row.errorType());
        source.addValue("errorMessage", row.errorMessage());
        source.addValue("isError", row.isError());
        source.addValue("retries", row.retries());
        source.addValue("latencyMs", row.latencyMs());
        source.addValue("sourceExternalId", row.sourceExternalId());
        source.addValue("eventTs", row.eventTs());
        source.addValue("isDeleted", row.isDeleted());
        source.addValue("attributes", row.attributes());
        source.addValue("startedAt", row.startedAt());
        source.addValue("createdAt", row.createdAt());
        source.addValue("traceId", row.traceId());
        source.addValue("spanId", row.spanId());
        return source;
    }

    /**
     * One JDBC batch of {@link #insert}s (#984 M2).
     */
    public void insertAll(List<ToolCallRow> rows) {
        if (rows.isEmpty()) return;
        int[] applied = named.batchUpdate(
                INSERT_SQL, rows.stream().map(ToolCallRepository::params).toArray(SqlParameterSource[]::new));
        BatchCounts.requireReal(applied);
    }

    public void insert(ToolCallRow row) {
        jdbc.sql(INSERT_SQL).paramSource(params(row)).update();
    }

    public Optional<ToolCallRow> findById(String projectId, String id) {
        return jdbc.sql("SELECT " + COLS + " FROM tool_call WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** One tool call together with the producer span id it hangs off — the detail read's grouping key. */
    public record SpanToolCall(@Nullable String spanId, ToolCallRow row) {}

    /**
     * All tool calls of one trace, by producer key.
     *
     * <p><b>No join to the v1 {@code observation} table.</b> The previous shape was
     * {@code tool_call tc JOIN observation o ON o.id = tc.observation_id WHERE o.trace_id = :tid} — a join
     * through the v1 substrate on a bare, unscoped surrogate trace id, so the read was neither
     * project-scoped nor able to survive the v1 tables going away, and they are now gone. The side table carries the producer
     * keys directly now ({@code trace_id}/{@code span_id} — written by the backfill's phase-C sweep for
     * history and by ingest since the v2 writer took over extraction), which makes this a single-table
     * indexed read on {@code ix_tool_call_trace}, project-scoped as every v2 key is.
     */
    public List<SpanToolCall> listByTrace(String projectId, String traceId) {
        return jdbc.sql("SELECT " + COLS + " FROM tool_call"
                        + " WHERE project_id = :pid AND trace_id = :tid"
                        + " ORDER BY started_at ASC NULLS LAST, created_at ASC")
                .param("pid", projectId)
                .param("tid", traceId)
                .query((rs, n) -> {
                    ToolCallRow row = map(rs);
                    return new SpanToolCall(row.spanId(), row);
                })
                .list();
    }

    /**
     * The batched sibling of {@link #listByTrace}, for a set of traces (a session's) in one query. No
     * {@code session_id} column exists on this table, and none is needed — {@code trace_id} already leads
     * its primary key, so {@code trace_id = ANY(:ids)} is the same read path as the single-trace lookup.
     */
    public List<SpanToolCall> listByTraceIds(String projectId, Collection<String> traceIds) {
        if (traceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLS + " FROM tool_call"
                        + " WHERE project_id = :pid AND trace_id = ANY(:ids)"
                        + " ORDER BY started_at ASC NULLS LAST, created_at ASC")
                .param("pid", projectId)
                .param("ids", traceIds.toArray(String[]::new))
                .query((rs, n) -> {
                    ToolCallRow row = map(rs);
                    return new SpanToolCall(row.spanId(), row);
                })
                .list();
    }

    private static ToolCallRow map(ResultSet rs) throws SQLException {
        return new ToolCallRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("name"),
                rs.getString("tool_call_id"),
                rs.getString("tool_type"),
                rs.getString("scope"),
                rs.getString("arguments"),
                rs.getString("arguments_raw"),
                rs.getString("result"),
                rs.getString("error_type"),
                rs.getString("error_message"),
                (Boolean) rs.getObject("is_error"),
                intOrNull(rs, "retries"),
                longOrNull(rs, "latency_ms"),
                rs.getString("source_external_id"),
                Timestamps.iso(rs, "event_ts"),
                (Boolean) rs.getObject("is_deleted"),
                rs.getString("attributes"),
                Timestamps.iso(rs, "started_at"),
                Timestamps.iso(rs, "created_at"),
                rs.getString("trace_id"),
                rs.getString("span_id"));
    }

    private static @Nullable Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static @Nullable Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
