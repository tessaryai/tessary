// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Guards;
import ai.tessary.endusers.judge.model.HitSet;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.GroupStats;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.PageStateResult;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.StateResult;
import ai.tessary.endusers.judge.model.StateResult.IdState;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.judge.model.TracerResult;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.BannerCode;
import ai.tessary.endusers.model.Grade;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.PageStateReason;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.model.StopStageState;
import ai.tessary.endusers.model.Stratum;
import ai.tessary.endusers.model.Substate;
import ai.tessary.endusers.model.TracerVerdict;
import ai.tessary.endusers.model.UserIdState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Every ID gets one display state by the first rule of spec 7.2 that applies, with the {@code at_risk} and
 * {@code slowing} rows left out (level 2). A stop cut read on the wrong odds before the first past date, a held rule
 * placed after the new rule, or a new rule that forgets its age limit or its exclusions puts an ID in the wrong stage.
 */
class StatesTest {

    private static final int D = 1000;
    private static final List<Integer> ANCHORS = List.of(931, 938, 945, 952, 959, 966);

    @Test
    void states_table() {
        Fixture f = new Fixture()
                .id(1, List.of(900, 940, 980), 0.80)
                .id(2, List.of(900, 940, 980), 0.60)
                .id(3, List.of(900, 940, 980), 0.59)
                .id(4, List.of(972, 980, 990), 0.29)
                .id(5, List.of(971, 980, 990), 0.29)
                .id(6, List.of(975, 980, 985, 990), 0.29);
        Map<Integer, IdState> s = f.states(false);
        assertAll(
                () -> assertEquals(state(UserIdState.STOPPED, null, Grade.STOPPED, Stage.STOPPED, 20), s.get(1)),
                () -> assertEquals(
                        state(UserIdState.STOPPED, null, Grade.PROBABLY_STOPPED, Stage.STOPPED, 20), s.get(2)),
                () -> assertEquals(state(UserIdState.ACTIVE, Substate.OVERDUE, null, Stage.SILENT, 20), s.get(3)),
                () -> assertEquals(state(UserIdState.NEW, null, null, Stage.ACTIVE, 10), s.get(4), "age 28"),
                () -> assertEquals(state(UserIdState.ACTIVE, null, null, Stage.ACTIVE, 10), s.get(5), "age 29"),
                () -> assertEquals(state(UserIdState.ACTIVE, null, null, Stage.ACTIVE, 10), s.get(6), "4 series days"));

        Fixture before = new Fixture().anchors(List.of());
        before.id(1, List.of(900, 940, 980), 0.80, 0.75, null, false, null, false);
        before.id(2, List.of(900, 940, 980), 0.79, 0.75, null, false, null, false);
        before.id(3, List.of(900, 940, 980), 0.85, 0.75, null, true, null, false);
        Map<Integer, IdState> b = before.states(false);
        assertAll(
                () -> assertEquals(
                        state(UserIdState.STOPPED, null, Grade.NOT_YET_CHECKED, Stage.STOPPED, 20),
                        b.get(1),
                        "before the first anchor p_nominal 0.80 stops"),
                () -> assertEquals(
                        state(UserIdState.ACTIVE, Substate.OVERDUE, null, Stage.SILENT, 20),
                        b.get(2),
                        "p_stop 0.75 alone does not stop before the first anchor"),
                () -> assertEquals(
                        state(UserIdState.STOPPED, null, Grade.NOT_YET_CHECKED, Stage.STOPPED, 20),
                        b.get(3),
                        "not yet checked wins over unattended"));
    }

    @Test
    void display_hasNoSlowingAndNoAtRiskState() {
        Fixture f = new Fixture()
                .id(1, List.of(900, 910, 920, 930, 940, 950, 960, 970, 980, 997), 0.10)
                .id(2, List.of(800, 801, 802, 803, 804, 805, 806, 807, 808, 990), 0.20)
                .id(3, List.of(940, 960, 980, 999), 0.05);
        Map<Integer, IdState> s = f.states(false);
        assertAll(
                () -> assertEquals(
                        state(UserIdState.ACTIVE, null, null, Stage.ACTIVE, 3),
                        s.get(1),
                        "the at_risk pattern shows as active"),
                () -> assertEquals(
                        state(UserIdState.ACTIVE, null, null, Stage.ACTIVE, 10),
                        s.get(2),
                        "the slowing pattern shows as active, with no slowing substate"),
                () -> assertEquals(state(UserIdState.ACTIVE, null, null, Stage.ACTIVE, 1), s.get(3)));
    }

    @Test
    void held() {
        Fixture f = new Fixture();
        f.id(1, List.of(900, 940, 980), 0.20, 0.20, 0.30, false, null, false);
        f.id(2, List.of(900, 940, 980), 0.20, 0.20, 0.29, false, null, false);
        f.id(3, List.of(990, 995), 0.10, 0.10, 0.35, false, null, false);
        f.id(4, List.of(900, 940, 980), 0.70, 0.70, 0.90, false, null, false);
        f.id(5, List.of(900, 940, 980), 0.20, 0.20, 0.50, false, SetAsideReason.SERVICE, false);
        Map<Integer, IdState> s = f.states(false);
        assertAll(
                () -> assertEquals(
                        state(UserIdState.ACTIVE, Substate.HELD, null, Stage.SILENT, 20),
                        s.get(1),
                        "calendar p_stop 0.30 is held"),
                () -> assertEquals(state(UserIdState.ACTIVE, null, null, Stage.ACTIVE, 20), s.get(2), "0.29 is not"),
                () -> assertEquals(
                        state(UserIdState.ACTIVE, Substate.HELD, null, Stage.SILENT, 5),
                        s.get(3),
                        "held wins over new"),
                () -> assertEquals(
                        state(UserIdState.STOPPED, null, Grade.PROBABLY_STOPPED, Stage.STOPPED, 20),
                        s.get(4),
                        "the stop rule comes before held"),
                () -> assertEquals(
                        new IdState(UserIdState.ACTIVE, null, null, null, 20, SetAsideReason.SERVICE, false),
                        s.get(5),
                        "a set-aside ID is never held and has no stage"));
    }

    @Test
    void new_excludesLeftCensored_bornInChange_andBornAfterAContinuedJob() {
        Fixture f = new Fixture();
        f.id(1, List.of(990, 995), 0.10, 0.10, null, false, null, true);
        f.id(2, List.of(990, 995), 0.10);
        f.id(3, List.of(990, 995), 0.10);
        f.id(4, List.of(990, 995), 0.30);
        f.id(5, List.of(990, 995), 0.10);
        f.bornInChange.add(2);
        f.bornAfterContinued.add(3);
        Map<Integer, IdState> s = f.states(false);
        IdState active = state(UserIdState.ACTIVE, null, null, Stage.ACTIVE, 5);
        assertAll(
                () -> assertEquals(active, s.get(1), "left-censored"),
                () -> assertEquals(active, s.get(2), "born in an ID change"),
                () -> assertEquals(active, s.get(3), "born after a continued job"),
                () -> assertEquals(
                        state(UserIdState.ACTIVE, Substate.OVERDUE, null, Stage.SILENT, 5), s.get(4), "p_stop 0.3"),
                () -> assertEquals(state(UserIdState.NEW, null, null, Stage.ACTIVE, 5), s.get(5)));
    }

    @Test
    void cannotFollow_isActiveFor28DaysThenUnknown_andInNoStage() {
        Fixture f = new Fixture().id(1, List.of(900, 940, 972), 0.90).id(2, List.of(900, 940, 971), 0.90);
        f.cannotFollow.addAll(Set.of(1, 2));
        f.censoredFrom.put(1, D - 28);
        f.censoredFrom.put(2, D - 29);
        Map<Integer, IdState> s = f.states(false);
        assertAll(
                () -> assertEquals(new IdState(UserIdState.ACTIVE, null, null, null, 28, null, true), s.get(1)),
                () -> assertEquals(new IdState(UserIdState.UNKNOWN, null, null, null, 29, null, true), s.get(2)));
    }

    @Test
    void refusedPage_givesEveryIdUnknownAndNoStage() {
        Fixture f = new Fixture().id(1, List.of(900, 940, 980), 0.90).id(2, List.of(972, 980, 990), 0.10);
        Map<Integer, IdState> s = f.states(true);
        assertAll(
                () -> assertEquals(new IdState(UserIdState.UNKNOWN, null, null, null, 20, null, false), s.get(1)),
                () -> assertEquals(new IdState(UserIdState.UNKNOWN, null, null, null, 10, null, false), s.get(2)));
    }

    @Test
    void judge_refusedPage_hasNoStagesNoStopStageAndOnlyTheRefusal() {
        Fixture f = new Fixture().id(1, List.of(900, 940, 980), 0.90).id(2, List.of(972, 980, 990), 0.10);
        PageStateResult page = new PageStateResult(
                PageState.TOO_EARLY,
                PageState.TOO_EARLY,
                PageStateReason.FEW_IDS,
                Map.of("ids", 2.0),
                false,
                List.of());
        StateResult r = f.judge(page);
        assertAll(
                () -> assertNull(r.stages()),
                () -> assertNull(r.stopStage()),
                () -> assertEquals(Map.of(), r.stopStageNumbers()),
                () -> assertEquals(UserIdState.UNKNOWN, r.byId().get(1).state()),
                () -> assertEquals(
                        List.of(new StateResult.Banner(BannerCode.PAGE_REFUSAL, Map.of("ids", 2.0))), r.banners()),
                () -> assertEquals(List.of(), r.limits()));
    }

    @Test
    void judge_usablePage_countsSetAsideByReason_andDecidesTheStopStage() {
        Fixture f = new Fixture()
                .id(1, List.of(900, 940, 980), 0.90)
                .id(2, List.of(972, 980, 990), 0.10)
                .id(3, List.of(900, 940, 980), 0.10, 0.10, null, false, SetAsideReason.SERVICE, false)
                .id(4, List.of(900, 940, 980), 0.10, 0.10, null, false, SetAsideReason.SERVICE, false)
                .id(5, List.of(900, 940, 980), 0.10, 0.10, null, false, SetAsideReason.AGGREGATE, false);
        StateResult r = f.judge(okPage());
        assertAll(
                () -> assertEquals(
                        Map.of(SetAsideReason.SERVICE, 2, SetAsideReason.AGGREGATE, 1), r.setAside(), "by reason"),
                () -> assertEquals(2, r.stages().counted()),
                () -> assertEquals(StopStageState.EXPECTED_AND_NAMED, r.stopStage()),
                () -> assertEquals(Stage.STOPPED, r.byId().get(1).stage()),
                () -> assertEquals(1.0, r.seasonLevel()));
    }

    @Test
    void usable_onlyOkAndPartialCoverage() {
        assertAll(
                () -> assertTrue(States.usable(PageState.OK)),
                () -> assertTrue(States.usable(PageState.PARTIAL_COVERAGE)),
                () -> assertFalse(States.usable(PageState.TOO_EARLY)),
                () -> assertFalse(States.usable(PageState.NO_USER_ID)),
                () -> assertFalse(States.usable(PageState.IDS_UNRELIABLE)),
                () -> assertFalse(States.usable(PageState.NOT_COMPUTED)),
                () -> assertFalse(States.usable(PageState.COLLECTING)));
    }

    private static PageStateResult okPage() {
        return new PageStateResult(PageState.OK, PageState.OK, null, Map.of(), false, List.of());
    }

    private static IdState state(
            UserIdState state, @Nullable Substate substate, @Nullable Grade grade, Stage stage, int daysSilent) {
        return new IdState(state, substate, grade, stage, daysSilent, null, false);
    }

    private static final class Fixture {
        final Map<Integer, IdSeries> series = new TreeMap<>();
        final Map<Integer, IdIdentity> identity = new TreeMap<>();
        final Map<Integer, IdOdds> odds = new TreeMap<>();
        final Set<Integer> cannotFollow = new TreeSet<>();
        final Map<Integer, Integer> censoredFrom = new TreeMap<>();
        final Set<Integer> bornInChange = new TreeSet<>();
        final Set<Integer> bornAfterContinued = new TreeSet<>();
        List<Integer> anchors = ANCHORS;

        Fixture anchors(List<Integer> days) {
            anchors = days;
            return this;
        }

        Fixture id(int u, List<Integer> days, double pStop) {
            return id(u, days, pStop, pStop, null, false, null, false);
        }

        Fixture id(
                int u,
                List<Integer> days,
                double pNominal,
                double pStop,
                @Nullable Double pStopCalendar,
                boolean unattended,
                @Nullable SetAsideReason setAside,
                boolean leftCensored) {
            series.put(
                    u,
                    new IdSeries(
                            days.getFirst(),
                            days,
                            ActivityBasis.HUMAN,
                            days,
                            List.of(),
                            List.of(),
                            leftCensored,
                            null));
            identity.put(
                    u, new IdIdentity(false, false, false, false, false, "(app)", null, setAside == null, setAside));
            odds.put(
                    u,
                    new IdOdds(
                            Stratum.E,
                            pNominal,
                            pStop,
                            pStop,
                            pStopCalendar,
                            unattended,
                            false,
                            D - days.getLast(),
                            D - days.getLast()));
            return this;
        }

        Map<Integer, IdState> states(boolean refused) {
            return States.byId(D, seriesResult(), identityResult(), channel(), tracer(), oddsResult(), refused);
        }

        StateResult judge(PageStateResult page) {
            return States.judge(
                    facts(),
                    seriesResult(),
                    identityResult(),
                    channel(),
                    tracer(),
                    page,
                    new HitSet(Map.of()),
                    new SilenceResult(List.of(), Map.of()),
                    oddsResult(),
                    Guards.allOn());
        }

        Series seriesResult() {
            return new Series(800, series);
        }

        Identity identityResult() {
            return new Identity(
                    identity,
                    Map.of(),
                    Map.of(),
                    new GroupStats(0, 0, null, null, null, null, false),
                    Map.of(),
                    1.0,
                    0.0,
                    0.0,
                    1.0,
                    1.0,
                    0.0);
        }

        IdChannelResult channel() {
            return new IdChannelResult(null, null, censoredFrom, cannotFollow, bornInChange);
        }

        TracerResult tracer() {
            return new TracerResult(TracerVerdict.NO_CHANGE_SEEN, 10, 0, false, null, null, bornAfterContinued);
        }

        StopOdds oddsResult() {
            return new StopOdds(
                    odds, 7.0, 7.0, 21, anchors, List.of(), 0.95, 0.9, Map.of(Stratum.E, 0.9), Map.of(), Map.of());
        }

        Facts facts() {
            List<Facts.IdInfo> ids = new ArrayList<>();
            for (int u = 0;
                    u
                            <= series.keySet().stream()
                                    .mapToInt(Integer::intValue)
                                    .max()
                                    .orElse(0);
                    u++) {
                IdSeries s = series.get(u);
                int first = s == null ? 800 : s.firstSeen();
                int last = s == null ? 800 : s.days().getLast();
                ids.add(new Facts.IdInfo("u", 8, null, first, last, 1, false));
            }
            return new Facts(
                    D,
                    800,
                    null,
                    ids,
                    new IdDays(
                            new int[0],
                            new int[0],
                            new int[0],
                            new int[0],
                            new int[0],
                            new int[0],
                            new int[0],
                            new int[0],
                            new long[0],
                            new int[0],
                            new int[0][0],
                            new long[0][0]),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new RouteClasses(Map.of(), false),
                    List.of(),
                    List.of(),
                    List.of());
        }
    }
}
