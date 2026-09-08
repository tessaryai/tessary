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
 * JdbcClient repository for the v2 {@code session} table (substrate-model.md §5.2).
 *
 * <p>Two kinds of write only, and neither is an accumulation. {@link #getOrCreate} (and its batch form) is
 * {@code ON CONFLICT DO NOTHING} on the natural key, so a redelivered batch is a no-op and the row is in
 * place before any trace can reference it (§6.1 resolution order — sessions, then traces, then spans).
 * {@link #touchAll} folds a whole drained batch's window into one {@code LEAST}/{@code GREATEST} pair per
 * session.
 *
 * <p>Nothing here is written in production in this release: the v2 write path arrives with
 * {@code SpanBatchWriter}.
 */
@Repository
public class SessionRepository {

    private static final String COLS = "project_id, id, user_id, started_at, last_activity_at, event_ts, is_deleted";

    private final JdbcClient jdbc;

    private final NamedParameterJdbcTemplate named;

    public SessionRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    private static final String GET_OR_CREATE_SQL = """
                        INSERT INTO session (project_id, id, user_id, started_at,
                                             last_activity_at, event_ts, is_deleted)
                        VALUES (:pid, :id, :userId, :startedAt::timestamptz,
                                :lastActivityAt::timestamptz, :eventTs::timestamptz, :isDeleted)
                        ON CONFLICT (project_id, id) DO NOTHING
                        """;

    private static MapSqlParameterSource params(SessionRow row) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        source.addValue("pid", row.projectId());
        source.addValue("id", row.id());
        source.addValue("userId", row.userId());
        source.addValue("startedAt", row.startedAt());
        source.addValue("lastActivityAt", row.lastActivityAt());
        source.addValue("eventTs", row.eventTs());
        source.addValue("isDeleted", row.isDeleted());
        return source;
    }

    /**
     * One JDBC batch of {@link #getOrCreate} inserts, in the order given (the caller sorts, see SpanBatchWriter).
     */
    public void getOrCreateAll(List<SessionRow> rows) {
        if (rows.isEmpty()) return;
        int[] applied = named.batchUpdate(
                GET_OR_CREATE_SQL, rows.stream().map(SessionRepository::params).toArray(SqlParameterSource[]::new));
        BatchCounts.requireReal(applied);
    }

    /**
     * Get-or-create, identity fields only (§6.1 step 1).
     *
     * <p><b>Identity fields only</b> is the whole contract: an arrival never writes {@code started_at} or
     * {@code last_activity_at} through this path, because whichever batch happened to create the row would
     * otherwise stamp its own window permanently — a late-arriving span of an old session dragging the
     * session's recency backwards, and an earlier span arriving later never correcting a start time that is
     * already too late. Timing belongs to {@link #touchAll} and its {@code LEAST}/{@code GREATEST}.
     *
     * @return true when this call created the row.
     */
    public boolean getOrCreate(SessionRow row) {
        return jdbc.sql(GET_OR_CREATE_SQL).paramSource(params(row)).update() > 0;
    }

    /**
     * One session's window as folded by the batch, pre-aggregated in memory by the caller.
     *
     * @param minStartedAt the earliest {@code started_at} among the batch's spans for this session.
     * @param lastActivityAt the latest timestamp seen for it in this drain.
     */
    public record Touch(String sessionId, String minStartedAt, String lastActivityAt) {}

    /**
     * Fold a batch's window into one {@code LEAST(started_at, …)} / {@code GREATEST(last_activity_at, …)}
     * per session (§7.1).
     *
     * <p><b>One update per session per batch, not one per span.</b> A 1,000-span batch touching 50
     * sessions issues one statement covering 50 rows, not 1,000 statements. The values are pre-aggregated
     * in memory by the caller; this is the write.
     *
     * <p><b>The window only ever widens.</b> The start moves earlier and the activity later, never the
     * reverse, so a span that arrives out of order corrects the row instead of corrupting it. Both halves
     * are idempotent under replay by construction, which is what makes a redelivered batch a no-op.
     *
     * <p><b>Sorted key order, before the update.</b> The explicit {@code FOR UPDATE} in
     * {@code ORDER BY project_id, id} is what makes deadlocks impossible between two concurrent batches
     * whose session sets overlap: every batch takes the same rows in the same order, so one always waits
     * rather than the two waiting on each other. The lock and the update are two statements and must run
     * in one transaction — the caller owns that boundary.
     *
     * @return the number of session rows updated.
     */
    public int touchAll(String projectId, Collection<Touch> touches) {
        if (touches.isEmpty()) {
            return 0;
        }
        List<Touch> sorted = touches.stream()
                .sorted(java.util.Comparator.comparing(Touch::sessionId))
                .toList();

        var lock = jdbc.sql("SELECT 1 FROM session WHERE project_id = :pid AND id IN (:ids)"
                        + " ORDER BY project_id, id FOR UPDATE")
                .param("pid", projectId)
                .param("ids", sorted.stream().map(Touch::sessionId).toList());
        lock.query().listOfRows();

        StringBuilder values = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            values.append(i == 0 ? "" : ", ")
                    .append("(:sid")
                    .append(i)
                    .append(", :st")
                    .append(i)
                    .append("::timestamptz, :ts")
                    .append(i)
                    .append("::timestamptz)");
        }
        var spec = jdbc.sql("""
                        UPDATE session s SET
                            started_at       = LEAST(s.started_at, v.min_started_at),
                            last_activity_at = GREATEST(s.last_activity_at, v.seen)
                        FROM (VALUES """ + values + """
                        ) AS v (session_id, min_started_at, seen)
                        WHERE s.project_id = :pid AND s.id = v.session_id
                        """).param("pid", projectId);
        for (int i = 0; i < sorted.size(); i++) {
            Touch t = sorted.get(i);
            spec = spec.param("sid" + i, t.sessionId())
                    .param("st" + i, t.minStartedAt())
                    .param("ts" + i, t.lastActivityAt());
        }
        return spec.update();
    }

    public Optional<SessionRow> findById(String projectId, String id) {
        return jdbc.sql("SELECT " + COLS + " FROM session WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** A project's sessions, most recently active first — served by {@code ix_session_project_active}. */
    public List<SessionRow> listByProject(String projectId, int limit) {
        return listByProject(projectId, limit, null, null);
    }

    /**
     * A page of the project's sessions, most recently active first, keyset-paginated on
     * {@code (last_activity_at, id)} — served entirely by {@code ix_session_project_active}.
     *
     * <p><b>There is no sort parameter, and that is contractual (§7.5).</b> Sessions carry no rollup: a
     * trace goes quiet in seconds, whereas a session may be resumed days later, so there is no gap of
     * inactivity that honestly means "finished" and a session rollup could never legitimately settle.
     * Recency is therefore the only ordering this table can serve without a materialization that would
     * need its own staleness contract. Ordering by cost or tokens would mean summing every session's
     * traces before the page could be chosen — a scan of the whole project per request, which is exactly
     * the read shape the v2 substrate exists to make impossible.
     */
    public List<SessionRow> listByProject(
            String projectId, int limit, @Nullable String beforeActivityAt, @Nullable String beforeId) {
        boolean paged = beforeActivityAt != null && beforeId != null;
        var where = new StringBuilder("WHERE project_id = :pid AND NOT is_deleted");
        if (paged) {
            where.append(" AND (last_activity_at < :beforeAt::timestamptz"
                    + " OR (last_activity_at = :beforeAt::timestamptz AND id < :beforeId))");
        }
        var spec = jdbc.sql("SELECT " + COLS + " FROM session " + where
                        + " ORDER BY last_activity_at DESC, id DESC LIMIT :limit")
                .param("pid", projectId)
                .param("limit", limit);
        if (beforeActivityAt != null && beforeId != null) {
            spec = spec.param("beforeAt", beforeActivityAt).param("beforeId", beforeId);
        }
        return spec.query((rs, n) -> map(rs)).list();
    }

    private static SessionRow map(ResultSet rs) throws SQLException {
        return new SessionRow(
                rs.getString("project_id"),
                rs.getString("id"),
                rs.getString("user_id"),
                requireIso(rs, "started_at"),
                requireIso(rs, "last_activity_at"),
                requireIso(rs, "event_ts"),
                rs.getBoolean("is_deleted"));
    }

    /** A NOT NULL timestamptz column, read back as ISO-8601. */
    static String requireIso(ResultSet rs, String column) throws SQLException {
        String iso = Timestamps.iso(rs, column);
        if (iso == null) {
            throw new SQLException("NOT NULL column " + column + " read back null");
        }
        return iso;
    }
}
