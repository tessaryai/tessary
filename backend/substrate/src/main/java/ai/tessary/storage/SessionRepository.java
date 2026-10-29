// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Neither write accumulates: {@link #getOrCreateAll} is {@code ON CONFLICT DO NOTHING}, and {@link #touchAll} folds a
 * batch's window with {@code LEAST}/{@code GREATEST}.
 */
@Repository
public class SessionRepository {

    private static final String COLS = "project_id, id, user_id, started_at, last_activity_at, event_ts, is_deleted";

    private final JdbcClient jdbc;

    private final NamedParameterJdbcTemplate named;

    private final TraceFilters filters;

    public SessionRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named, TraceFilters filters) {
        this.jdbc = jdbc;
        this.named = named;
        this.filters = filters;
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
     * Identity fields only: whichever batch created the row would otherwise stamp its window permanently. Timing
     * belongs to {@link #touchAll}.
     */
    public void getOrCreateAll(List<SessionRow> rows) {
        if (rows.isEmpty()) return;
        int[] applied = named.batchUpdate(
                GET_OR_CREATE_SQL, rows.stream().map(SessionRepository::params).toArray(SqlParameterSource[]::new));
        BatchCounts.requireReal(applied);
    }

    public record Touch(String sessionId, String minStartedAt, String lastActivityAt) {}

    /**
     * The window only widens, so out-of-order spans correct the row and replay is a no-op. The explicit
     * {@code FOR UPDATE ORDER BY project_id, id} prevents deadlocks between overlapping batches; lock and update must
     * share the caller's transaction.
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

    /**
     * No sort parameter by contract: sessions carry no rollup, so ordering by cost or tokens would sum every session in
     * the project per request.
     */
    @Transactional(readOnly = true)
    public List<SessionRow> listByProject(
            String projectId,
            TraceV2Repository.TraceQuery filter,
            int limit,
            @Nullable String beforeActivityAt,
            @Nullable String beforeId) {
        var params = new HashMap<String, Object>();
        params.put("pid", projectId);
        params.put("limit", limit);
        var where = new StringBuilder("WHERE s.project_id = :pid AND NOT s.is_deleted");
        if (beforeActivityAt != null && beforeId != null) {
            where.append(" AND (s.last_activity_at < :beforeAt::timestamptz"
                    + " OR (s.last_activity_at = :beforeAt::timestamptz AND s.id < :beforeId))");
            params.put("beforeAt", beforeActivityAt);
            params.put("beforeId", beforeId);
        }
        try {
            if (TraceFilters.narrows(filter)) {
                filters.appendForSessions(projectId, where, params, filter);
            }
            String cols = Arrays.stream(COLS.split(", ")).map(c -> "s." + c).collect(Collectors.joining(", "));
            return jdbc.sql("SELECT " + cols + " FROM session s " + where
                            + " ORDER BY s.last_activity_at DESC, s.id DESC LIMIT :limit")
                    .params(params)
                    .query((rs, n) -> map(rs))
                    .list();
        } catch (DataAccessException e) {
            throw TraceFilters.searchFailure(e);
        }
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

    static String requireIso(ResultSet rs, String column) throws SQLException {
        return Objects.requireNonNull(Timestamps.iso(rs, column), column);
    }
}
