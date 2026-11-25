// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.judge.EndUserJudge.Judgment;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.HitRow;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import ai.tessary.endusers.judge.model.Facts.IssueInput;
import ai.tessary.endusers.judge.model.Facts.NullDay;
import ai.tessary.endusers.judge.model.Guards;
import ai.tessary.endusers.judge.model.IssueResult.LineageResult;
import ai.tessary.endusers.judge.model.PageStateResult;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.RouteClasses.Route;
import ai.tessary.endusers.judge.model.RouteClasses.RouteStatistics;
import ai.tessary.endusers.judge.model.StateResult.IdState;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.model.RouteClass;
import ai.tessary.endusers.model.UserIdState;
import ai.tessary.endusers.model.Verdict;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The judge run: the stages in the fixed order of spec 3, and what a refused page leaves of them. A stage that reads a
 * later stage's output, or a refused page that still names states or issue numbers, shows numbers the spec says the page
 * must not show.
 */
class EndUserJudgeTest {

    private static final int D = 100;
    private static final RouteStatistics STATISTICS =
            new RouteStatistics(0, 0, 0, 0, 0, 0, 0, null, null, null, 0, null);

    @Test
    void chainOrder_followsTheSpec_andARefusedPageGivesNoStatesAndRefusedIssues() {
        List<String> stages = new ArrayList<>();
        Facts facts = threeIdFacts(Map.of(1, RouteClass.A));

        Judgment j = EndUserJudge.judge(facts, Guards.allOn(), (name, rows, durationMs) -> stages.add(name));

        LineageResult a = j.issues().byLineage().get("lineage-a");
        LineageResult b = j.issues().byLineage().get("lineage-b");
        assertAll(
                () -> assertEquals(
                        List.of(
                                "routes",
                                "aliases",
                                "day_series",
                                "flags",
                                "groups",
                                "id_channel",
                                "tracer",
                                "page_state",
                                "issue_hits",
                                "shared_silence",
                                "stop_odds",
                                "past_check",
                                "states",
                                "issues"),
                        stages,
                        "aliases run before day series: the day series of an alias member is its representative's"),
                () -> assertEquals(PageState.TOO_EARLY, j.page().state(), "3 IDs are under the 20 of spec 3.5"),
                () -> assertEquals(
                        List.of(UserIdState.UNKNOWN, UserIdState.UNKNOWN, UserIdState.UNKNOWN),
                        j.states().byId().values().stream().map(IdState::state).toList()),
                () -> assertTrue(
                        j.states().byId().values().stream().allMatch(s -> s.stage() == null),
                        "a refused page puts no ID in a stage"),
                () -> assertNull(j.states().stages()),
                () -> assertEquals(Verdict.PAGE_REFUSED, a.verdict()),
                () -> assertEquals(2, a.usersHit(), "a refused page still gives every hit user ID"),
                () -> assertFalse(a.shown()),
                () -> assertNull(a.lost()),
                () -> assertNull(a.low()),
                () -> assertNull(a.high()),
                () -> assertEquals(Verdict.PAGE_REFUSED, b.verdict()),
                () -> assertEquals(1, b.usersHit(), "a ref to a session only hits the session's user ID"),
                () -> assertEquals(List.of(1, 2), List.of(a.rank(), b.rank())));
    }

    @Test
    void overBudget_onlyAboveTheMaximum() {
        assertAll(
                () -> assertFalse(EndUserJudge.overBudget(5_000_000, 5_000_000), "the maximum itself is judged"),
                () -> assertTrue(EndUserJudge.overBudget(5_000_001, 5_000_000)));
    }

    @Test
    void withoutIds_trafficWithNoUserId_isNoUserIdWithTheShareThatFired() {
        PageStateResult page =
                EndUserJudge.withoutIds(D, 20, List.of(new NullDay(D, 1, 1, 40, 40)), classes(Map.of(1, RouteClass.A)));

        assertEquals(
                new PageStateResult(
                        PageState.NO_USER_ID,
                        PageState.NO_USER_ID,
                        PageStateReason.LOW_ID_SHARE,
                        Map.of("ident_share", 0.0),
                        false,
                        List.of()),
                page);
    }

    @Test
    void withoutIds_erasedIdsInOlderTraffic_refuseOnTheLast28Days() {
        PageStateResult page = EndUserJudge.withoutIds(
                D,
                20,
                List.of(new NullDay(D - 60, 1, 1, 40, 0), new NullDay(D, 1, 1, 40, 40)),
                classes(Map.of(1, RouteClass.A)));

        assertEquals(
                new PageStateResult(
                        PageState.NO_USER_ID,
                        PageState.NO_USER_ID,
                        PageStateReason.LOW_ID_SHARE_28,
                        Map.of("ident_share_28", 0.0),
                        false,
                        List.of()),
                page,
                "half of all traffic carries an ID, none of the last 28 days does");
    }

    @Test
    void withoutIds_trafficOnlyOnClassC_isTooEarly_becauseTheShareReadsOtherRoutesOnly() {
        PageStateResult page =
                EndUserJudge.withoutIds(D, 20, List.of(new NullDay(D, 1, 1, 40, 40)), classes(Map.of(1, RouteClass.C)));

        assertEquals(
                new PageStateResult(
                        PageState.TOO_EARLY,
                        PageState.TOO_EARLY,
                        PageStateReason.FEW_IDS,
                        Map.of("span_days", 80.0, "ids", 0.0, "span_days_needed", 21.0, "ids_needed", 20.0),
                        false,
                        List.of()),
                page);
    }

    /**
     * Three IDs on route 1 from day 41 to {@code D}: ID 0 every day, ID 1 every second day, ID 2 every third. Issue a
     * hit IDs 0 and 1 and one trace with no user ID; issue b hit ID 2 through a ref to a session only.
     */
    private static Facts threeIdFacts(Map<Integer, RouteClass> routes) {
        List<int[]> rows = new ArrayList<>();
        for (int u = 0; u < 3; u++) {
            for (int d = 41; d <= D; d += u + 1) rows.add(new int[] {u, d});
        }
        int n = rows.size();
        int[] id = new int[n];
        int[] day = new int[n];
        int[] route = new int[n];
        int[] version = new int[n];
        int[] traces = new int[n];
        int[] input = new int[n];
        int[] hours = new int[n];
        long[] slots = new long[n];
        Map<Integer, Integer> perDay = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            id[i] = rows.get(i)[0];
            day[i] = rows.get(i)[1];
            route[i] = 1;
            version[i] = 1;
            traces[i] = 2;
            input[i] = 2;
            hours[i] = 1 << (9 + id[i]);
            slots[i] = 1L << (36 + 4 * id[i]);
            perDay.merge(day[i], 2, Integer::sum);
        }
        IdDays idDays = new IdDays(
                id,
                day,
                route,
                version,
                traces,
                input,
                new int[n],
                hours,
                slots,
                new int[n],
                new int[n][0],
                new long[n][0]);
        List<NullDay> nullDays = new ArrayList<>();
        perDay.forEach((d, t) -> nullDays.add(new NullDay(d, 1, 1, t + 1, 1)));
        List<IdInfo> ids = List.of(
                new IdInfo("usr", 9, null, 41, D, 0, true),
                new IdInfo("usr", 9, null, 41, 98, 0, true),
                new IdInfo("usr", 9, null, 41, 98, 0, true));
        List<IssueInput> issues = List.of(
                new IssueInput("lineage-a", "case-a", List.of("f-a"), true, false),
                new IssueInput("lineage-b", "case-b", List.of("f-b"), true, false));
        List<HitRow> hits = List.of(
                new HitRow(0, 0, 80, 1, Map.of(), 1),
                new HitRow(0, 1, 85, 1, Map.of(), 2),
                new HitRow(0, null, 86, 1, Map.of(), 1),
                new HitRow(1, 2, 89, null, Map.of(1, 3), 1));
        return new Facts(
                D,
                40,
                null,
                ids,
                idDays,
                nullDays,
                List.of(),
                List.of(new Facts.Version(1, "v-1", 40)),
                issues,
                hits,
                classes(routes),
                List.of(),
                List.of(),
                List.of());
    }

    private static RouteClasses classes(Map<Integer, RouteClass> routes) {
        Map<Integer, Route> byRoute = new TreeMap<>();
        routes.forEach((no, rc) -> byRoute.put(no, new Route(rc, null, STATISTICS, false)));
        return new RouteClasses(byRoute, routes.values().stream().noneMatch(rc -> rc == RouteClass.A));
    }
}
