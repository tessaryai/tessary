// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.read;

import ai.tessary.endusers.judge.model.StateResult.Banner;
import ai.tessary.endusers.judge.model.StateResult.LimitLine;
import ai.tessary.endusers.judge.model.StateResult.StageCounts;
import ai.tessary.endusers.judge.model.StateResult.TrustNumbers;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.StopStageState;
import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The newest {@code end_user_snapshot} of a project and its history start day. Reads only; no row is created. */
@Repository
public class SnapshotReadRepository {

    private final JdbcClient jdbc;
    private final ReadJson json;

    public SnapshotReadRepository(JdbcClient jdbc, ReadJson json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record SnapshotRow(
            int day,
            PageState pageState,
            @Nullable PageStateReason reason,
            Map<String, Double> reasonNumbers,
            List<Banner> banners,
            List<LimitLine> limits,
            @Nullable TrustNumbers trust,
            @Nullable StageCounts stages,
            @Nullable Map<SetAsideReason, Integer> setAside,
            @Nullable StopStageState stopStage,
            @Nullable Map<String, Double> stopStageNumbers) {}

    public Optional<SnapshotRow> latest(String projectId) {
        return jdbc.sql("""
                SELECT day, page_state, page_state_reason, reason_numbers, banners, limits, trust, stage_counts,
                       set_aside, stop_stage_state, stop_stage_numbers
                  FROM end_user_snapshot
                 WHERE project_id = :pid
                 ORDER BY day DESC
                 LIMIT 1
                """).param("pid", projectId).query((rs, n) -> map(rs)).optional();
    }

    /** The trace name of each asked route of the project that has one. */
    public Map<Integer, String> routeNames(String projectId, Collection<Integer> routeNos) {
        Map<Integer, String> out = new TreeMap<>();
        jdbc.sql("""
                SELECT route_no, trace_name FROM end_user_route
                 WHERE project_id = :pid AND route_no = ANY(:routes) AND trace_name IS NOT NULL
                """)
                .param("pid", projectId)
                .param("routes", routeNos.toArray(Integer[]::new))
                .query((rs, n) -> Map.entry(rs.getInt("route_no"), rs.getString("trace_name")))
                .list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    /** Empty when the project has no {@code end_user_project} row or its start day is not fixed yet. */
    public Optional<Integer> historyStartDay(String projectId) {
        return jdbc.sql("SELECT history_start_day FROM end_user_project WHERE project_id = :pid")
                .param("pid", projectId)
                .query((rs, n) -> {
                    int day = rs.getInt("history_start_day");
                    return rs.wasNull() ? Optional.<Integer>empty() : Optional.of(day);
                })
                .optional()
                .flatMap(day -> day);
    }

    private SnapshotRow map(ResultSet rs) throws SQLException {
        String reason = rs.getString("page_state_reason");
        String stopStage = rs.getString("stop_stage_state");
        return new SnapshotRow(
                rs.getInt("day"),
                PageState.fromWire(rs.getString("page_state")),
                reason == null ? null : PageStateReason.fromWire(reason),
                json.read(rs.getString("reason_numbers"), new TypeReference<Map<String, Double>>() {}),
                json.read(rs.getString("banners"), new TypeReference<List<Banner>>() {}),
                json.read(rs.getString("limits"), new TypeReference<List<LimitLine>>() {}),
                json.readNullable(rs.getString("trust"), new TypeReference<TrustNumbers>() {}),
                json.readNullable(rs.getString("stage_counts"), new TypeReference<StageCounts>() {}),
                json.readNullable(rs.getString("set_aside"), new TypeReference<Map<SetAsideReason, Integer>>() {}),
                stopStage == null ? null : StopStageState.fromWire(stopStage),
                json.readNullable(rs.getString("stop_stage_numbers"), new TypeReference<Map<String, Double>>() {}));
    }
}
