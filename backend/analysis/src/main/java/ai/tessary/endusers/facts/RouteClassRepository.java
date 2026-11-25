// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.RouteClasses.Route;
import ai.tessary.endusers.judge.model.RouteClasses.RouteStatistics;
import ai.tessary.endusers.model.RouteClass;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The learned class, class-since day, statistics and near-cut flag on the {@code end_user_route} rows. */
@Repository
public class RouteClassRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public RouteClassRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Every route with a stored class; the project is lenient when none of them is of class A. */
    public RouteClasses stored(String projectId) {
        Map<Integer, Route> routes = new TreeMap<>();
        jdbc.sql("""
                SELECT route_no, route_class, class_since_day, statistics::text AS statistics, near_cut
                  FROM end_user_route
                 WHERE project_id = :pid AND route_class IS NOT NULL
                """).param("pid", projectId).query(rs -> {
            routes.put(
                    rs.getInt("route_no"),
                    new Route(
                            RouteClass.fromWire(rs.getString("route_class")),
                            rs.getObject("class_since_day", Integer.class),
                            read(rs.getString("statistics")),
                            rs.getBoolean("near_cut")));
        });
        boolean hasA = routes.values().stream().anyMatch(r -> r.routeClass() == RouteClass.A);
        return new RouteClasses(routes, !hasA);
    }

    /** The routes with no stored class that have a day row in {@code [fromDay, toDay)}. */
    public List<Integer> unclassedWithDayRows(String projectId, int fromDay, int toDay) {
        return jdbc.sql("""
                SELECT r.route_no FROM end_user_route r
                 WHERE r.project_id = :pid AND r.route_class IS NULL
                   AND EXISTS (SELECT 1 FROM end_user_day_null n
                                WHERE n.project_id = r.project_id AND n.route_no = r.route_no
                                  AND n.day >= :from AND n.day < :to)
                 ORDER BY r.route_no
                """)
                .param("pid", projectId)
                .param("from", fromDay)
                .param("to", toDay)
                .query(Integer.class)
                .list();
    }

    /** Writes the given routes in one transaction. */
    @Transactional
    public void save(String projectId, Map<Integer, Route> routes) {
        routes.forEach((routeNo, route) -> jdbc.sql("""
                        UPDATE end_user_route
                           SET route_class = :class, class_since_day = :since, statistics = CAST(:stats AS jsonb),
                               near_cut = :near, updated_at = now()
                         WHERE project_id = :pid AND route_no = :no
                        """)
                .param("class", route.routeClass().wire())
                .param("since", route.classSinceDay())
                .param("stats", write(route.statistics()))
                .param("near", route.nearCut())
                .param("pid", projectId)
                .param("no", routeNo)
                .update());
    }

    private RouteStatistics read(String json) {
        try {
            return mapper.readValue(json, RouteStatistics.class);
        } catch (JsonProcessingException e) {
            throw new TessaryException(CommonError.INTERNAL, e);
        }
    }

    private String write(RouteStatistics statistics) {
        try {
            return mapper.writeValueAsString(statistics);
        } catch (JsonProcessingException e) {
            throw new TessaryException(CommonError.INTERNAL, e);
        }
    }
}
