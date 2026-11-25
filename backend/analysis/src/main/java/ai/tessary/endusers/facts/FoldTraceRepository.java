// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The trace reads of the fold. Only settled, not deleted traces count, and every read is a range on
 * {@code started_at}, so it runs on {@code ix_trace_project_started_id} (or {@code ix_trace_session} for sessions).
 */
@Repository
public class FoldTraceRepository {

    private static final long SECONDS_PER_DAY = 86_400;
    private static final String LIVE = "is_settled AND is_deleted IS NOT TRUE";
    private static final String COLUMNS = """
            user_id, session_id, thread_id, name, call_site_id, project_version_id, started_at, input_preview,
            (parent_trace_id IS NOT NULL AND parent_trace_id <> '') AS has_parent""";

    private final JdbcClient jdbc;

    public FoldTraceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The traces that start on {@code day}, in start order. */
    public List<FoldTrace> dayTraces(String projectId, int day) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM trace
                        WHERE project_id = :pid AND started_at >= :from AND started_at < :to AND
                        """ + LIVE + " ORDER BY started_at, id")
                .param("pid", projectId)
                .param("from", dayStart(day))
                .param("to", dayStart(day + 1))
                .query((rs, n) -> map(rs))
                .list();
    }

    /** The traces with a user ID of these sessions and of these threads that start in {@code [fromDay, toDay)}. */
    public List<FoldTrace> groupTraces(
            String projectId, Collection<String> sessionIds, Collection<String> threadIds, int fromDay, int toDay) {
        List<FoldTrace> traces = new ArrayList<>();
        if (!sessionIds.isEmpty()) traces.addAll(groupTraces(projectId, "session_id", sessionIds, fromDay, toDay));
        if (!threadIds.isEmpty()) traces.addAll(groupTraces(projectId, "thread_id", threadIds, fromDay, toDay));
        return traces;
    }

    /** The UTC day of the first trace on or after {@code fromDay}; with no {@code fromDay}, of the first trace. */
    public Optional<Integer> firstTraceDay(String projectId, @Nullable Integer fromDay) {
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT started_at FROM trace WHERE project_id = :pid AND " + LIVE
                        + (fromDay == null ? "" : " AND started_at >= :from") + " ORDER BY started_at LIMIT 1")
                .param("pid", projectId);
        if (fromDay != null) spec = spec.param("from", dayStart(fromDay));
        return spec.query((rs, n) -> Math.toIntExact(
                        Math.floorDiv(rs.getObject(1, OffsetDateTime.class).toEpochSecond(), SECONDS_PER_DAY)))
                .optional();
    }

    /**
     * The UTC days in {@code [fromDay, toDay)} (no lower end when {@code fromDay} is null) that hold a trace created or
     * rolled up after the given instants.
     */
    public Set<Integer> changedDays(
            String projectId, @Nullable Integer fromDay, int toDay, Instant createdAfter, Instant rolledUpAfter) {
        JdbcClient.StatementSpec spec = jdbc.sql(
                        "SELECT DISTINCT CAST(floor(extract(epoch FROM started_at) / 86400) AS integer)"
                                + " FROM trace WHERE project_id = :pid AND started_at < :to AND " + LIVE
                                + (fromDay == null ? "" : " AND started_at >= :from")
                                + " AND (created_at > :created OR rolled_up_at > :rolled)")
                .param("pid", projectId)
                .param("to", dayStart(toDay))
                .param("created", createdAfter.atOffset(ZoneOffset.UTC))
                .param("rolled", rolledUpAfter.atOffset(ZoneOffset.UTC));
        if (fromDay != null) spec = spec.param("from", dayStart(fromDay));
        return new TreeSet<>(spec.query(Integer.class).list());
    }

    private List<FoldTrace> groupTraces(
            String projectId, String column, Collection<String> ids, int fromDay, int toDay) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM trace WHERE project_id = :pid AND " + column + " = ANY(:ids)"
                        + " AND started_at >= :from AND started_at < :to AND user_id IS NOT NULL AND " + LIVE)
                .param("pid", projectId)
                .param("ids", ids.toArray(String[]::new))
                .param("from", dayStart(fromDay))
                .param("to", dayStart(toDay))
                .query((rs, n) -> map(rs))
                .list();
    }

    private static OffsetDateTime dayStart(int day) {
        return Instant.ofEpochSecond(day * SECONDS_PER_DAY).atOffset(ZoneOffset.UTC);
    }

    private static FoldTrace map(ResultSet rs) throws SQLException {
        return new FoldTrace(
                rs.getString("user_id"),
                rs.getString("session_id"),
                rs.getString("thread_id"),
                rs.getString("name"),
                rs.getString("call_site_id"),
                rs.getString("project_version_id"),
                rs.getObject("started_at", OffsetDateTime.class).toEpochSecond(),
                rs.getString("input_preview"),
                rs.getBoolean("has_parent"));
    }
}
