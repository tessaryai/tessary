// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * JdbcClient repository for the first-class {@code retrieved_doc} table — the retrieved passages of a
 * RAG (retrieval/reranker) span. Append-only insert plus a trace-scoped read.
 *
 * <p>Idempotent insert: {@code ON CONFLICT DO NOTHING} against the primary key, derived from
 * {@code (project, trace, span, seq)} ({@code SideTableIds}), makes the write path's at-least-once
 * replays first-write-wins no-ops. The old natural key {@code (observation_id, seq)} went with the
 * column. jsonb/timestamptz columns carry {@code String} bound with explicit casts.
 */
@Repository
public class RetrievedDocRepository {

    private static final String COLS = "id, project_id, seq, list_role, rank, doc_id, "
            + "title, content, score, source_uri, data_source_id, metadata, "
            + "source_external_id, event_ts, is_deleted, created_at, trace_id, span_id";

    private final JdbcClient jdbc;

    private final NamedParameterJdbcTemplate named;

    public RetrievedDocRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    private static final String INSERT_SQL = """
            INSERT INTO retrieved_doc (id, project_id, seq, list_role, rank, doc_id,
                                       title, content, score, source_uri, data_source_id,
                                       metadata, source_external_id, event_ts, is_deleted, created_at,
                                       trace_id, span_id)
            VALUES (:id, :pid, :seq, :listRole, :rank, :docId, :title, :content,
                    :score, :sourceUri, :dataSourceId, :metadata::jsonb,
                    :sourceExternalId, :eventTs::timestamptz, :isDeleted, :createdAt::timestamptz,
                    :traceId, :spanId)
            ON CONFLICT DO NOTHING
            """;

    private static MapSqlParameterSource params(RetrievedDocRow row) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        source.addValue("id", row.id());
        source.addValue("pid", row.projectId());
        source.addValue("seq", row.seq());
        source.addValue("listRole", row.listRole());
        source.addValue("rank", row.rank());
        source.addValue("docId", row.docId());
        source.addValue("title", row.title());
        source.addValue("content", row.content());
        source.addValue("score", row.score());
        source.addValue("sourceUri", row.sourceUri());
        source.addValue("dataSourceId", row.dataSourceId());
        source.addValue("metadata", row.metadata());
        source.addValue("sourceExternalId", row.sourceExternalId());
        source.addValue("eventTs", row.eventTs());
        source.addValue("isDeleted", row.isDeleted());
        source.addValue("createdAt", row.createdAt());
        source.addValue("traceId", row.traceId());
        source.addValue("spanId", row.spanId());
        return source;
    }

    /**
     * One JDBC batch of {@link #insert}s (#984 M2).
     */
    public void insertAll(List<RetrievedDocRow> rows) {
        if (rows.isEmpty()) return;
        int[] applied = named.batchUpdate(
                INSERT_SQL, rows.stream().map(RetrievedDocRepository::params).toArray(SqlParameterSource[]::new));
        BatchCounts.requireReal(applied);
    }

    public void insert(RetrievedDocRow row) {
        jdbc.sql(INSERT_SQL).paramSource(params(row)).update();
    }

    /** One retrieved passage together with the producer span id it belongs to. */
    public record SpanRetrievedDoc(@Nullable String spanId, RetrievedDocRow row) {}

    /**
     * All retrieved docs of one trace, by producer key — the same re-keying as
     * {@link ToolCallRepository#listByTrace}, and for the same reason: the old shape joined the v1
     * {@code observation} table on a bare unscoped surrogate trace id.
     */
    public List<SpanRetrievedDoc> listByTrace(String projectId, String traceId) {
        return jdbc.sql("SELECT " + COLS + " FROM retrieved_doc"
                        + " WHERE project_id = :pid AND trace_id = :tid"
                        + " ORDER BY seq ASC NULLS LAST, created_at ASC")
                .param("pid", projectId)
                .param("tid", traceId)
                .query((rs, n) -> {
                    RetrievedDocRow row = map(rs);
                    return new SpanRetrievedDoc(row.spanId(), row);
                })
                .list();
    }

    /**
     * The batched sibling of {@link #listByTrace}, for a set of traces (a session's) in one query — same
     * reasoning as {@link ToolCallRepository#listByTraceIds}: no {@code session_id} column, none needed,
     * {@code trace_id} already leads the primary key.
     */
    public List<SpanRetrievedDoc> listByTraceIds(String projectId, Collection<String> traceIds) {
        if (traceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLS + " FROM retrieved_doc"
                        + " WHERE project_id = :pid AND trace_id = ANY(:ids)"
                        + " ORDER BY seq ASC NULLS LAST, created_at ASC")
                .param("pid", projectId)
                .param("ids", traceIds.toArray(String[]::new))
                .query((rs, n) -> {
                    RetrievedDocRow row = map(rs);
                    return new SpanRetrievedDoc(row.spanId(), row);
                })
                .list();
    }

    private static RetrievedDocRow map(ResultSet rs) throws SQLException {
        return new RetrievedDocRow(
                rs.getString("id"),
                rs.getString("project_id"),
                intOrNull(rs, "seq"),
                rs.getString("list_role"),
                intOrNull(rs, "rank"),
                rs.getString("doc_id"),
                rs.getString("title"),
                rs.getString("content"),
                (Double) rs.getObject("score"),
                rs.getString("source_uri"),
                rs.getString("data_source_id"),
                rs.getString("metadata"),
                rs.getString("source_external_id"),
                Timestamps.iso(rs, "event_ts"),
                (Boolean) rs.getObject("is_deleted"),
                Timestamps.iso(rs, "created_at"),
                rs.getString("trace_id"),
                rs.getString("span_id"));
    }

    private static @Nullable Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
