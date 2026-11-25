// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import ai.tessary.endusers.judge.JudgeConstants;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

/**
 * The trace reads of the learn step, over the settled, not deleted traces that start in {@code [fromDay, toDay)}. Times
 * are whole epoch seconds, as in the reference. A user ID or session ID is trimmed of the white space
 * {@link EndUserKeys#normalise} trims, and an empty one is none; no ID text leaves the database.
 */
@Repository
public class RouteTraceRepository {

    /** Every character {@link EndUserKeys#normalise} trims, as the character set of {@code btrim}. */
    private static final String WHITE_SPACE = IntStream.range(1, Character.MAX_VALUE + 1)
            .filter(c -> EndUserKeys.normalise(String.valueOf((char) c)) == null)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();

    private static final String WINDOW = """
            t.project_id = :pid AND t.started_at >= :from AND t.started_at < :to AND t.is_settled
            AND t.is_deleted IS NOT TRUE
            """;

    private final JdbcClient jdbc;

    public RouteTraceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The counts of each route that has a trace in the window. A reply delay is measured from the latest end (the start
     * when there is no end) of the earlier traces of the same session on any route, in start order.
     */
    public List<RouteTally> tallies(String projectId, int fromDay, int toDay) {
        return tallies(projectId, fromDay, toDay, null);
    }

    /**
     * {@link #tallies(String, int, int)} of the given registered routes only. The other routes' traces still give the
     * reply delays of these routes, so the filter applies after the delays are measured.
     */
    public List<RouteTally> tallies(String projectId, int fromDay, int toDay, @Nullable Collection<Integer> routeNos) {
        String only = routeNos == null ? "" : """
                 WHERE EXISTS (SELECT 1 FROM end_user_route r
                                WHERE r.project_id = :pid AND r.route_no = ANY(:routes) AND r.call_site_id = f.cs
                                  AND r.trace_name IS NOT DISTINCT FROM f.name)
                """;
        StatementSpec sql = jdbc.sql("""
                WITH w AS (
                    SELECT t.id, t.started_at, t.name, COALESCE(t.call_site_id, :unattributed) AS cs,
                           NULLIF(btrim(t.user_id, :ws), '') IS NULL AS no_user,
                           NULLIF(btrim(t.session_id, :ws), '') AS sid,
                           CAST(floor(extract(epoch FROM t.started_at)) AS bigint) AS s,
                           CAST(floor(extract(epoch FROM COALESCE(t.ended_at, t.started_at))) AS bigint) AS e,
                           (t.input_preview IS NOT NULL AND t.input_preview <> '') AS has_input,
                           (t.parent_trace_id IS NOT NULL AND t.parent_trace_id <> '') AS has_parent
                      FROM trace t
                     WHERE
                """ + WINDOW + """
                ), f AS (
                    SELECT w.*, s - max(e) OVER (PARTITION BY sid ORDER BY started_at, id
                                                 ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS delay
                      FROM w
                )
                SELECT name, cs, count(*) AS traces,
                       count(*) FILTER (WHERE no_user) AS null_ids,
                       count(*) FILTER (WHERE has_input) AS with_input,
                       count(*) FILTER (WHERE has_parent) AS with_parent,
                       count(*) FILTER (WHERE s % 60 = 0) AS second0,
                       count(*) FILTER (WHERE (s / 60) % 5 = 0) AS minute5,
                       COALESCE(array_agg(delay ORDER BY delay)
                                FILTER (WHERE sid IS NOT NULL AND delay BETWEEN 0 AND :reply), '{}') AS delays
                  FROM f
                """ + only + """
                 GROUP BY name, cs
                 ORDER BY name NULLS FIRST, cs
                """)
                .param("pid", projectId)
                .param("from", dayStart(fromDay))
                .param("to", dayStart(toDay))
                .param("unattributed", RouteRegistryRepository.UNATTRIBUTED)
                .param("ws", WHITE_SPACE)
                .param("reply", JudgeConstants.REPLY_WINDOW_SECONDS);
        if (routeNos != null) sql = sql.param("routes", routeNos.toArray(Integer[]::new));
        return sql.query((rs, n) -> new RouteTally(
                        rs.getString("name"),
                        rs.getString("cs"),
                        rs.getInt("traces"),
                        rs.getInt("null_ids"),
                        rs.getInt("with_input"),
                        rs.getInt("with_parent"),
                        rs.getInt("second0"),
                        rs.getInt("minute5"),
                        delays(rs.getArray("delays"))))
                .list();
    }

    /**
     * For each registered route and user ID in the window, the first traces of the ID on the route in start order, at
     * most {@link JudgeConstants#PAIR_TEST_MAX_TRACES}.
     */
    public List<RouteSample> samples(String projectId, int fromDay, int toDay) {
        return samples(projectId, fromDay, toDay, null);
    }

    /** {@link #samples(String, int, int)} of the given registered routes only. */
    public List<RouteSample> samples(String projectId, int fromDay, int toDay, @Nullable Collection<Integer> routeNos) {
        String only = routeNos == null ? "" : " AND r.route_no = ANY(:routes)";
        StatementSpec sql = jdbc.sql("""
                WITH ranked AS (
                    SELECT r.route_no, NULLIF(btrim(t.user_id, :ws), '') AS uid,
                           CAST(floor(extract(epoch FROM t.started_at)) AS bigint) AS s,
                           row_number() OVER (PARTITION BY r.route_no, NULLIF(btrim(t.user_id, :ws), '')
                                              ORDER BY t.started_at, t.id) AS rank_no
                      FROM trace t
                      JOIN end_user_route r ON r.project_id = t.project_id
                                           AND r.call_site_id = COALESCE(t.call_site_id, :unattributed)
                                           AND r.trace_name IS NOT DISTINCT FROM t.name
                     WHERE NULLIF(btrim(t.user_id, :ws), '') IS NOT NULL""" + only + " AND " + WINDOW + """
                )
                SELECT route_no, array_agg(CAST((s % 86400) / 60 AS integer) ORDER BY rank_no) AS minutes,
                       array_agg(CAST(s / 86400 AS integer) ORDER BY rank_no) AS days
                  FROM ranked
                 WHERE rank_no <= :cap
                 GROUP BY route_no, uid
                """)
                .param("pid", projectId)
                .param("from", dayStart(fromDay))
                .param("to", dayStart(toDay))
                .param("unattributed", RouteRegistryRepository.UNATTRIBUTED)
                .param("ws", WHITE_SPACE)
                .param("cap", JudgeConstants.PAIR_TEST_MAX_TRACES);
        if (routeNos != null) sql = sql.param("routes", routeNos.toArray(Integer[]::new));
        return sql.query((rs, n) ->
                        new RouteSample(rs.getInt("route_no"), ints(rs.getArray("minutes")), ints(rs.getArray("days"))))
                .list();
    }

    /**
     * For each of {@code bRoutes} with a trace of a user ID in the window: the share of its distinct (user ID, day)
     * pairs on which the ID also has a trace on one of {@code aRoutes}.
     */
    public Map<Integer, Double> anchorShares(
            String projectId, int fromDay, int toDay, Collection<Integer> aRoutes, Collection<Integer> bRoutes) {
        Map<Integer, Double> shares = new TreeMap<>();
        jdbc.sql("""
                WITH d AS (
                    SELECT DISTINCT r.route_no, NULLIF(btrim(t.user_id, :ws), '') AS uid,
                           CAST(floor(extract(epoch FROM t.started_at) / 86400) AS integer) AS day
                      FROM trace t
                      JOIN end_user_route r ON r.project_id = t.project_id
                                           AND r.call_site_id = COALESCE(t.call_site_id, :unattributed)
                                           AND r.trace_name IS NOT DISTINCT FROM t.name
                     WHERE NULLIF(btrim(t.user_id, :ws), '') IS NOT NULL
                       AND (r.route_no = ANY(:a) OR r.route_no = ANY(:b)) AND
                """ + WINDOW + """
                ), a AS (
                    SELECT DISTINCT uid, day FROM d WHERE route_no = ANY(:a)
                )
                SELECT d.route_no, CAST(count(a.uid) AS float8) / count(*) AS share
                  FROM d LEFT JOIN a ON a.uid = d.uid AND a.day = d.day
                 WHERE d.route_no = ANY(:b)
                 GROUP BY d.route_no
                """)
                .param("pid", projectId)
                .param("from", dayStart(fromDay))
                .param("to", dayStart(toDay))
                .param("unattributed", RouteRegistryRepository.UNATTRIBUTED)
                .param("ws", WHITE_SPACE)
                .param("a", aRoutes.toArray(Integer[]::new))
                .param("b", bRoutes.toArray(Integer[]::new))
                .query((ResultSet rs) -> {
                    shares.put(rs.getInt("route_no"), rs.getDouble("share"));
                });
        return shares;
    }

    private static OffsetDateTime dayStart(int day) {
        return Instant.ofEpochSecond(day * (long) JudgeConstants.SECONDS_PER_DAY)
                .atOffset(ZoneOffset.UTC);
    }

    private static double[] delays(Array array) throws SQLException {
        Object[] values = (Object[]) array.getArray();
        double[] delays = new double[values.length];
        for (int i = 0; i < values.length; i++) delays[i] = ((Number) values[i]).doubleValue();
        return delays;
    }

    private static int[] ints(Array array) throws SQLException {
        Object[] values = (Object[]) array.getArray();
        int[] ints = new int[values.length];
        for (int i = 0; i < values.length; i++) ints[i] = ((Number) values[i]).intValue();
        return ints;
    }
}
