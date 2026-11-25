// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.RouteClasses.Route;
import ai.tessary.endusers.judge.model.RouteClasses.RouteStatistics;
import ai.tessary.endusers.model.RouteClass;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The learn step against real Postgres: route statistics from the live traces of the 28 days up to the as-of day, the
 * class rules, and the stored row. A trace read outside the window, a reply delay measured from the wrong trace, or a
 * pair test on the wrong 150 traces gives a route the wrong class, and every trace of that route then counts on the
 * wrong side of a person's activity.
 */
@SpringBootTest
record RouteLearnerTest(
        TenantService tenants,
        JdbcClient jdbc,
        RouteLearner learner,
        RouteClassRepository classes,
        RouteRegistryRepository registry) {

    private static final int D = 20619;

    @Test
    void learn_classesAndStatistics_fromTheLiveTracesOfTheLast28Days() {
        Project p = TenantFixture.bootstrap(tenants, "learn-classes").project();
        Project other = TenantFixture.bootstrap(tenants, "learn-classes-other").project();
        for (int k = 0; k <= 20; k++) {
            Instant open = at(D - k, "10:01:07").plusSeconds(420L * k);
            Instant turn = open.plusSeconds(5 + 10L * (k + 1));
            insert(p, "open" + k, "s" + k, "u1", "chat.open", "open", open, open.plusSeconds(5), true, false);
            insert(p, "turn" + k, "s" + k, "u1", "chat.turn", "turn", turn, null, true, false);
        }
        insert(p, "old", null, "u1", "chat.turn", "turn", at(D - 28, "12:00:00"), null, true, false);
        insert(p, "today", null, "u1", "chat.turn", "turn", at(D + 1, "12:00:00"), null, true, false);
        insert(p, "unsettled", null, "u1", "chat.turn", "turn", at(D - 3, "12:00:00"), null, false, false);
        insert(p, "deleted", null, "u1", "chat.turn", "turn", at(D - 3, "13:00:00"), null, true, true);
        for (int d = D - 5; d <= D; d++) {
            insert(p, "cron" + d, null, "svc", "nightly", "cron", at(d, "03:07:13"), null, true, false);
        }
        insert(p, "in1", "si", null, "ingest", null, at(D - 2, "08:00:00"), null, true, false);
        insert(p, "in2", "si", " ", "ingest", null, at(D - 2, "08:01:40"), null, true, false);
        insert(p, "in3", "si", null, "ingest", null, at(D - 2, "09:30:00"), null, true, false);
        insert(other, "x1", null, "u1", "chat.turn", "turn", at(D - 1, "12:00:00"), null, true, false);
        int otherRoute = registry.routeNo(other.id(), "chat.turn", "turn");

        RouteClasses learned = learner.learn(p.id(), D);

        RouteClasses expected = new RouteClasses(
                Map.of(
                        route(p, "chat.open", "open"),
                        new Route(
                                RouteClass.A2,
                                D,
                                new RouteStatistics(21, 0.0, 0.0, 0.0, 0.0, 4.0 / 21, 0.0, null, null, 0.0, 0.0, 1.0),
                                false),
                        route(p, "chat.turn", "turn"),
                        new Route(
                                RouteClass.A,
                                D,
                                new RouteStatistics(21, 0.0, 0.0, 0.0, 0.0, 5.0 / 21, 1.0, 30.0, 190.0, 0.0, 0.0, null),
                                false),
                        route(p, "nightly", "cron"),
                        new Route(
                                RouteClass.S,
                                D,
                                new RouteStatistics(6, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null, null, 1.0, 1.0, null),
                                false),
                        route(p, "ingest", null),
                        new Route(
                                RouteClass.C,
                                D,
                                new RouteStatistics(
                                        3, 1.0, 0.0, 0.0, 2.0 / 3, 2.0 / 3, 1.0 / 3, null, null, null, 0.0, null),
                                true)),
                false);
        assertAll(
                () -> assertEquals(expected, learned),
                () -> assertEquals(expected, classes.stored(p.id()), "the stored row reads back field for field"),
                () -> assertEquals(List.of("ingest|__unattributed__|C|" + D + "|true|null|null"), rows(p, """
                                SELECT trace_name || '|' || call_site_id || '|' || route_class || '|' || class_since_day
                                       || '|' || near_cut || '|' || jsonb_typeof(statistics -> 'dq10') || '|'
                                       || jsonb_typeof(statistics -> 'conc')
                                  FROM end_user_route WHERE project_id = :pid AND trace_name = 'ingest'
                                """)),
                () -> assertEquals(
                        List.of(otherRoute + "|null|null|{}|false"),
                        rows(other, """
                                SELECT route_no || '|' || coalesce(route_class, 'null') || '|'
                                       || coalesce(class_since_day::text, 'null') || '|' || statistics::text || '|'
                                       || near_cut
                                  FROM end_user_route WHERE project_id = :pid
                                """),
                        "another project's routes are not learned"));
    }

    @Test
    void pairTest_readsTheFirst150TracesOfAnIdInTimeOrder() {
        Project p = TenantFixture.bootstrap(tenants, "learn-pair-cap").project();
        for (int d = D - 12; d <= D; d++) {
            insert(p, "late" + d, null, "p1", "poll", "poll", at(d, "05:31:00"), null, true, false);
        }
        for (int d = D - 27; d <= D - 13; d++) {
            for (int i = 0; i < 10; i++) {
                insert(p, "early" + d + "-" + i, null, "p1", "poll", "poll", at(d, "05:01:0" + i), null, true, false);
            }
        }

        RouteClasses learned = learner.learn(p.id(), D);

        assertEquals(
                new RouteClasses(
                        Map.of(
                                route(p, "poll", "poll"),
                                new Route(
                                        RouteClass.S,
                                        D,
                                        new RouteStatistics(
                                                163, 0.0, 0.0, 0.0, 28.0 / 163, 0.0, 0.0, null, null, 1.0, 1.0, null),
                                        false)),
                        true),
                learned,
                "the 13 later traces at another minute are past the first 150, so the concentration stays 1");
    }

    @Test
    void learn_withNoARoute_isLenient_andABRouteHasAnchorShare0() {
        Project p = TenantFixture.bootstrap(tenants, "learn-lenient").project();
        insert(p, "e1", null, "p1", "export", "export", at(D - 3, "14:23:17"), null, true, false);
        insert(p, "e2", null, "p1", "export", "export", at(D - 2, "16:41:29"), null, true, false);

        RouteClasses learned = learner.learn(p.id(), D);

        assertEquals(
                new RouteClasses(
                        Map.of(
                                route(p, "export", "export"),
                                new Route(
                                        RouteClass.B,
                                        D,
                                        new RouteStatistics(
                                                2, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null, null, null, 0.0, 0.0),
                                        false)),
                        true),
                learned);
    }

    @Test
    void learn_keepsTheStoredClass_insideTheMargin() {
        Project p = TenantFixture.bootstrap(tenants, "learn-margin").project();
        for (int k = 0; k <= 20; k++) {
            Instant open = at(D - k, "10:01:07").plusSeconds(420L * k);
            Instant turn = open.plusSeconds(5 + 10L * (k + 1));
            insert(p, "open" + k, "s" + k, "u1", "chat.open", "open", open, open.plusSeconds(5), true, false);
            insert(p, "turn" + k, "s" + k, "u1", "chat.turn", "turn", turn, null, true, false);
        }
        for (int i = 0; i < 54; i++) {
            Instant at = at(D - (i % 20), "15:00:00").plusSeconds(61L * i + 7);
            insert(p, "solo" + i, null, "u2", "chat.turn", "turn", at, null, true, false);
        }
        int turn = registry.routeNo(p.id(), "chat.turn", "turn");
        jdbc.sql("UPDATE end_user_route SET route_class = 'A', class_since_day = :since"
                        + " WHERE project_id = :pid AND route_no = :no")
                .param("since", D - 10)
                .param("pid", p.id())
                .param("no", turn)
                .update();

        Route learned = learner.learn(p.id(), D).byRoute().get(turn);

        assertAll(
                () -> assertEquals(0.28, learned.statistics().follow(), 1e-12, "21 replies of 75 traces"),
                () -> assertEquals(RouteClass.A, learned.routeClass(), "follow 0.28 is inside the margin of 0.3"),
                () -> assertEquals(D - 10, learned.classSinceDay()),
                () -> assertTrue(learned.nearCut()));
    }

    private int route(Project p, String name, @Nullable String callSite) {
        return registry.routeNo(p.id(), name, callSite);
    }

    private void insert(
            Project p,
            String id,
            @Nullable String session,
            @Nullable String userId,
            String name,
            @Nullable String callSite,
            Instant started,
            @Nullable Instant ended,
            boolean settled,
            boolean deleted) {
        if (session != null) {
            jdbc.sql("""
                    INSERT INTO session (project_id, id, started_at, last_activity_at, event_ts)
                    VALUES (:pid, :id, :at, :at, :at) ON CONFLICT DO NOTHING
                    """)
                    .param("pid", p.id())
                    .param("id", session)
                    .param("at", Timestamp.from(started))
                    .update();
        }
        jdbc.sql("""
                INSERT INTO trace (project_id, id, session_id, user_id, name, call_site_id, started_at, ended_at,
                                   event_ts, is_settled, is_deleted, created_at)
                VALUES (:pid, :id, :session, :user, :name, :cs, :at, :ended, :at, :settled, :deleted, :at)
                """)
                .param("pid", p.id())
                .param("id", id)
                .param("session", session)
                .param("user", userId)
                .param("name", name)
                .param("cs", callSite)
                .param("at", Timestamp.from(started))
                .param("ended", ended == null ? null : Timestamp.from(ended))
                .param("settled", settled)
                .param("deleted", deleted)
                .update();
    }

    private static Instant at(int day, String time) {
        return Instant.parse("1970-01-01T" + time + "Z").plusSeconds(day * 86_400L);
    }

    private List<String> rows(Project p, String sql) {
        return jdbc.sql(sql).param("pid", p.id()).query(String.class).list();
    }
}
