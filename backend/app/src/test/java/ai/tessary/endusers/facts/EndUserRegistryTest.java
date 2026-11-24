// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.tessary.endusers.model.PageState;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The per-project state row and the route and version numbers against real Postgres. Every fact row names its route
 * and version by number and its user ID by the salted key, so a number that changes between runs, or a second salt,
 * silently splits one route or one user ID into two.
 */
@SpringBootTest
record EndUserRegistryTest(
        TenantService tenants, JdbcClient jdbc, EndUserProjectRepository projects, RouteRegistryRepository registry) {

    @Test
    void routeNo_getOrCreate_isStable() {
        Project p = TenantFixture.bootstrap(tenants, "end-user-route-no").project();

        int reply = registry.routeNo(p.id(), "chat.turn", "chat_reply");
        int again = registry.routeNo(p.id(), "chat.turn", "chat_reply");
        int unattributed = registry.routeNo(p.id(), "chat.turn", null);
        int unnamed = registry.routeNo(p.id(), null, null);

        assertAll(
                () -> assertEquals(reply, again, "one (name, call site) keeps one number"),
                () -> assertEquals(List.of(1, 2, 3), List.of(reply, unattributed, unnamed)),
                () -> assertEquals(unnamed, registry.routeNo(p.id(), null, null), "a NULL name is one route too"),
                () -> assertEquals(3, routeRows(p)),
                () -> assertEquals("__unattributed__", callSiteOf(p, unattributed)));
    }

    @Test
    void salt_isCreatedOnce_andIs32Bytes() {
        Project p = TenantFixture.bootstrap(tenants, "end-user-salt").project();
        Project other = TenantFixture.bootstrap(tenants, "end-user-salt-other").project();

        byte[] first = projects.salt(p.id());
        byte[] second = projects.salt(p.id());

        assertAll(
                () -> assertEquals(32, first.length),
                () -> assertArrayEquals(first, second, "a second salt would split every user ID in two"),
                () -> assertEquals(1, stateRows(p)),
                () -> assertFalse(Arrays.equals(first, projects.salt(other.id())), "each project has its own salt"));
    }

    @Test
    void versionNo_getOrCreate_keepsTheEarliestFirstDay() {
        Project p = TenantFixture.bootstrap(tenants, "end-user-version-no").project();

        int v1 = registry.versionNo(p.id(), "v1.5", 20525);
        int v1Earlier = registry.versionNo(p.id(), "v1.5", 20513);
        int v1Later = registry.versionNo(p.id(), "v1.5", 20530);
        int none = registry.versionNo(p.id(), null, 20520);

        assertAll(
                () -> assertEquals(List.of(1, 1, 1, 2), List.of(v1, v1Earlier, v1Later, none)),
                () -> assertEquals(none, registry.versionNo(p.id(), null, 20540), "a NULL version is one version too"),
                () -> assertEquals(20513, firstTraceDayOf(p, v1), "the deploy day is the earliest trace day seen"),
                () -> assertEquals(20520, firstTraceDayOf(p, none)));
    }

    @Test
    void projectState_roundTripsEveryField() {
        Project p = TenantFixture.bootstrap(tenants, "end-user-project-state").project();
        Instant hits = Instant.parse("2026-11-24T01:02:03.123456Z");
        Instant created = Instant.parse("2026-11-24T02:00:00.000001Z");
        Instant rollup = Instant.parse("2026-11-24T03:30:00Z");

        EndUserProjectRepository.State fresh = projects.state(p.id());
        int fixed = projects.fixHistoryStartDay(p.id(), 20513);
        int kept = projects.fixHistoryStartDay(p.id(), 20400);
        projects.setHitsWatermark(p.id(), hits);
        projects.setFoldWatermarks(p.id(), created, rollup);
        projects.rememberPageState(p.id(), PageState.OK);
        projects.rememberPageState(p.id(), PageState.IDS_UNRELIABLE);
        projects.rememberPageState(p.id(), PageState.TOO_EARLY);

        assertAll(
                () -> assertEquals(new EndUserProjectRepository.State(null, null, null, null, List.of()), fresh),
                () -> assertEquals(20513, fixed),
                () -> assertEquals(20513, kept, "the history start day is fixed once and never moves"),
                () -> assertEquals(
                        new EndUserProjectRepository.State(
                                20513, hits, created, rollup, List.of(PageState.IDS_UNRELIABLE, PageState.TOO_EARLY)),
                        projects.state(p.id()),
                        "the memory keeps the last two page states, oldest first"));
    }

    private int routeRows(Project p) {
        return jdbc.sql("SELECT count(*) FROM end_user_route WHERE project_id = :pid")
                .param("pid", p.id())
                .query(Integer.class)
                .single();
    }

    private String callSiteOf(Project p, int routeNo) {
        return jdbc.sql("SELECT call_site_id FROM end_user_route WHERE project_id = :pid AND route_no = :no")
                .param("pid", p.id())
                .param("no", routeNo)
                .query(String.class)
                .single();
    }

    private int stateRows(Project p) {
        return jdbc.sql("SELECT count(*) FROM end_user_project WHERE project_id = :pid")
                .param("pid", p.id())
                .query(Integer.class)
                .single();
    }

    private int firstTraceDayOf(Project p, int versionNo) {
        return jdbc.sql("SELECT first_trace_day FROM end_user_version WHERE project_id = :pid AND version_no = :no")
                .param("pid", p.id())
                .param("no", versionNo)
                .query(Integer.class)
                .single();
    }
}
