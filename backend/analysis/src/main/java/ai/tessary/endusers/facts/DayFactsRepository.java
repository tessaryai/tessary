// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The fold's writes. {@link #replaceDay} deletes a day's fact rows and writes the new ones; the caller holds one
 * transaction around it and {@link #writeFoldDay}, so a reader sees the old day or the new day, never a mix. An
 * {@code end_user} row only widens: its day range takes the earliest first day and the latest last day ever folded, and
 * its session token link is the AND of every fold's, because spec 3.1 reads every session of the ID.
 */
@Repository
public class DayFactsRepository {

    private static final HexFormat HEX = HexFormat.of();

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;

    public DayFactsRepository(JdbcClient jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    public void replaceDay(String projectId, DayFacts facts) {
        for (String table : List.of("end_user_day", "end_user_day_null", "end_user_alias_edge")) {
            jdbc.sql("DELETE FROM " + table + " WHERE project_id = :pid AND day = :day")
                    .param("pid", projectId)
                    .param("day", facts.day())
                    .update();
        }
        batch(
                """
                INSERT INTO end_user_day (project_id, user_key, day, route_no, version_no, traces, session_hashes,
                                          hour_mask, slot_mask, job_keys, traces_with_input, traces_with_parent)
                VALUES (:pid, :key, :day, :route, :version, :traces, :sessions, :hours, CAST(:slots AS bit(96)), :jobs,
                        :input, :parent)
                """,
                facts.idDays().stream()
                        .map(row -> new MapSqlParameterSource()
                                .addValue("pid", projectId)
                                .addValue("key", HEX.parseHex(row.userKey()))
                                .addValue("day", facts.day())
                                .addValue("route", row.routeNo())
                                .addValue("version", row.versionNo())
                                .addValue("traces", row.traces())
                                .addValue("sessions", row.sessionHashes().toArray(Integer[]::new))
                                .addValue("hours", row.hourMask())
                                .addValue("slots", DayFacts.slotMaskText(row.slotMaskLow(), row.slotMaskHigh()))
                                .addValue("jobs", row.jobKeys().toArray(Long[]::new))
                                .addValue("input", row.tracesWithInput())
                                .addValue("parent", row.tracesWithParent()))
                        .toList());
        batch(
                """
                INSERT INTO end_user_day_null (project_id, day, route_no, version_no, traces, traces_no_user_id)
                VALUES (:pid, :day, :route, :version, :traces, :noId)
                """,
                facts.nullDays().stream()
                        .map(row -> new MapSqlParameterSource()
                                .addValue("pid", projectId)
                                .addValue("day", facts.day())
                                .addValue("route", row.routeNo())
                                .addValue("version", row.versionNo())
                                .addValue("traces", row.traces())
                                .addValue("noId", row.tracesNoUserId()))
                        .toList());
        batch(
                """
                INSERT INTO end_user_alias_edge (project_id, from_key, to_key, via, day)
                VALUES (:pid, :from, :to, :via, :day)
                """,
                facts.aliasEdges().stream()
                        .map(edge -> new MapSqlParameterSource()
                                .addValue("pid", projectId)
                                .addValue("from", HEX.parseHex(edge.fromKey()))
                                .addValue("to", HEX.parseHex(edge.toKey()))
                                .addValue("via", edge.via().wire())
                                .addValue("day", edge.day()))
                        .toList());
        batch(
                """
                INSERT INTO end_user (project_id, user_key, id_text, shape_signature, cluster_hash, first_seen_day,
                                      last_seen_day, session_token_link)
                VALUES (:pid, :key, :text, :shape, :cluster, :first, :last, :tokenLink)
                ON CONFLICT (project_id, user_key) DO UPDATE SET
                    id_text = EXCLUDED.id_text,
                    shape_signature = EXCLUDED.shape_signature,
                    cluster_hash = EXCLUDED.cluster_hash,
                    first_seen_day = LEAST(end_user.first_seen_day, EXCLUDED.first_seen_day),
                    last_seen_day = GREATEST(end_user.last_seen_day, EXCLUDED.last_seen_day),
                    session_token_link = end_user.session_token_link AND EXCLUDED.session_token_link,
                    updated_at = now()
                """,
                facts.users().stream()
                        .map(user -> new MapSqlParameterSource()
                                .addValue("pid", projectId)
                                .addValue("key", HEX.parseHex(user.userKey()))
                                .addValue("text", user.idText())
                                .addValue("shape", user.shapeSignature())
                                .addValue("cluster", hexOrNull(user.clusterHash()))
                                .addValue("first", user.firstDay())
                                .addValue("last", user.lastDay())
                                .addValue("tokenLink", user.sessionTokenLink()))
                        .toList());
    }

    /**
     * Per route number, its traces on each of the {@code days} days before {@code day}, oldest first, 0 for a day with
     * none.
     */
    public Map<Integer, int[]> routeTracesBefore(String projectId, int day, int days) {
        record Count(int day, int routeNo, int traces) {}
        List<Count> counts = jdbc.sql("""
                SELECT day, route_no, sum(traces) AS traces FROM end_user_day_null
                 WHERE project_id = :pid AND day >= :from AND day < :day
                 GROUP BY day, route_no
                """)
                .param("pid", projectId)
                .param("from", day - days)
                .param("day", day)
                .query((rs, n) -> new Count(rs.getInt("day"), rs.getInt("route_no"), rs.getInt("traces")))
                .list();
        Map<Integer, int[]> byRoute = new HashMap<>();
        for (Count c : counts) {
            byRoute.computeIfAbsent(c.routeNo(), r -> new int[days])[c.day() - (day - days)] = c.traces();
        }
        return byRoute;
    }

    public void writeFoldDay(String projectId, int day, Instant foldedAt, int traces, boolean outage) {
        jdbc.sql("""
                INSERT INTO end_user_fold_day (project_id, day, folded_at, traces, gap, outage)
                VALUES (:pid, :day, :at, :traces, false, :outage)
                ON CONFLICT (project_id, day) DO UPDATE SET
                    folded_at = EXCLUDED.folded_at, traces = EXCLUDED.traces, gap = false, outage = EXCLUDED.outage
                """)
                .param("pid", projectId)
                .param("day", day)
                .param("at", at(foldedAt))
                .param("traces", traces)
                .param("outage", outage)
                .update();
    }

    /** Days whose traces were deleted before any fold read them. A day that has a row keeps it. */
    public void markGaps(String projectId, List<Integer> days, Instant at) {
        jdbc.sql("""
                INSERT INTO end_user_fold_day (project_id, day, folded_at, traces, gap, outage)
                SELECT :pid, d, :at, 0, true, false FROM unnest(CAST(:days AS integer[])) AS d
                ON CONFLICT (project_id, day) DO NOTHING
                """)
                .param("pid", projectId)
                .param("at", at(at))
                .param("days", days.toArray(Integer[]::new))
                .update();
    }

    /** The latest day a fold read traces for; gap days do not count. */
    public Optional<Integer> lastFoldedDay(String projectId) {
        return jdbc.sql("SELECT max(day) FROM end_user_fold_day WHERE project_id = :pid AND NOT gap")
                .param("pid", projectId)
                .query((rs, n) -> {
                    int day = rs.getInt(1);
                    return rs.wasNull() ? Optional.<Integer>empty() : Optional.of(day);
                })
                .single();
    }

    private void batch(String sql, List<? extends SqlParameterSource> rows) {
        if (rows.isEmpty()) return;
        named.batchUpdate(sql, rows.toArray(SqlParameterSource[]::new));
    }

    private static byte @Nullable [] hexOrNull(@Nullable String hex) {
        return hex == null ? null : HEX.parseHex(hex);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
