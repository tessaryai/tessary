// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import ai.tessary.endusers.judge.model.HitSet;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.IdChannelResult.IdLoss;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.GroupStats;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.PageStateResult;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.RouteClasses.Route;
import ai.tessary.endusers.judge.model.RouteClasses.RouteStatistics;
import ai.tessary.endusers.judge.model.Scope;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.SilenceResult.ClockInterval;
import ai.tessary.endusers.judge.model.SilenceResult.SilenceEvent;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.PageState;
import ai.tessary.endusers.model.RouteClass;
import ai.tessary.endusers.model.ScopeKind;
import ai.tessary.endusers.model.SilenceState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Shared silence finds weeks in which many regular IDs go silent together and holds their silence as a possible break
 * (spec 4, reading 9 of spec 5). A panel frozen at the first event week, or one that keeps IDs of an ID loss or ID
 * change, keeps an event open on its own losses; a hold that never ends hides real stops; a missing return wave or
 * traffic test holds a schedule that stopped as if people paused.
 */
class SharedSilenceTest {

    private static final int PACED = 1;
    private static final int OTHER = 2;

    @Test
    void eventWeek() {
        Scenario s = new Scenario(83);
        for (int u = 0; u < 109; u++) {
            int block = u < 108 ? u / 9 : 12;
            boolean inWave = block == 10 || block == 0 || block == 1 || (u >= 18 && u < 22);
            s.id(d -> {
                int w = d / 7;
                if (w == 10) return !inWave;
                return w < 1 || block != w;
            });
        }

        SilenceResult result = s.find();
        SilenceResult refused = s.page(PageState.NO_USER_ID).find();

        SilenceEvent e = result.events().getFirst();
        assertAll(
                () -> assertTrue(SharedSilence.eventWeek(0.31, 0.09, 100, 30), "excess 0.22 >= 0.15 and >= 0.086"),
                () -> assertFalse(SharedSilence.eventWeek(0.23, 0.09, 100, 30), "excess 0.14 is under 0.15"),
                () -> assertFalse(SharedSilence.eventWeek(0.75, 0.5, 30, 30), "excess 0.25 is under 3 sigma, 0.274"),
                () -> assertFalse(SharedSilence.eventWeek(0.31, 0.09, 29, 30), "29 IDs are under 30"),
                () -> assertFalse(SharedSilence.eventWeek(0.31, null, 100, 30), "fewer than 3 usual weeks"),
                () -> assertEquals(1, result.events().size(), "9 of 100 silent each week is the usual share"),
                () -> assertEquals(new Scope(ScopeKind.APP, null), e.scope()),
                () -> assertEquals(100, e.panel(), "the 9 IDs silent in the week before are not in the panel"),
                () -> assertEquals(0.31, e.i(), 1e-12),
                () -> assertEquals(0.09, e.i0(), 1e-12),
                () -> assertEquals(0.710, e.rho(), 0.0005, "0.22 / 0.31"),
                () -> assertFalse(e.provisional()),
                () -> assertEquals(70, e.startDay(), "the median last series day plus 1"),
                () -> assertEquals(
                        40,
                        e.members().size(),
                        "the 31, and the 9 IDs whose last day is 76 and who are silent for the 7 days to D"),
                () -> assertEquals(SilenceState.PROVEN_BREAK, e.state(), "all 31 came back on day 77"),
                () -> assertEquals(77, e.endDay()),
                () -> assertEquals(List.of(), refused.events(), "a refused page holds nothing"),
                () -> assertEquals(Map.of(), refused.clock()));
    }

    @Test
    void noEvent_under30PanelIds() {
        SilenceResult cluster = clusterOf(20);
        SilenceResult small = clusterOf(19);

        SilenceEvent e = cluster.events().getFirst();
        assertAll(
                () -> assertEquals(1, cluster.events().size(), "29 IDs of the app are under 30"),
                () -> assertEquals(new Scope(ScopeKind.CLUSTER, "7"), e.scope(), "20 is enough in a cluster scope"),
                () -> assertEquals(20, e.panel()),
                () -> assertEquals(0.5, e.i(), 1e-12),
                () -> assertEquals(List.of(), small.events(), "a cluster of 19 IDs is no scope"));
    }

    @Test
    void hold_endsAt28Days_withoutReturnWave() {
        SilenceResult openResult = waveOf40(83).find();
        SilenceResult closed = waveOf40(84).find();
        SilenceEvent tooFew = waveOf40(91, 70, 70, 70, 70, 70, 70, 72, 75, 75, 75, 75)
                .find()
                .events()
                .getFirst();
        SilenceResult proven =
                waveOf40(91, 70, 70, 70, 70, 70, 70, 72, 75, 75, 75, 75, 75, 75).find();
        SilenceEvent even = waveOf40(91, 70, 70, 70, 70, 70, 70, 75, 75, 75, 75, 75, 75)
                .find()
                .events()
                .getFirst();

        SilenceEvent open = openResult.events().getFirst();
        SilenceEvent notABreak = closed.events().getFirst();
        SilenceEvent wave = proven.events().getFirst();
        assertAll(
                () -> assertEquals(SilenceState.OPEN, open.state(), "27 days after the start"),
                () -> assertEquals(56, open.startDay()),
                () -> assertEquals(1.0, open.rho(), 1e-12, "no ID is silent in a usual week"),
                () -> assertNull(open.endDay()),
                () -> assertEquals(84, open.coverEnd(), "an open event covers to D + 1"),
                () -> assertEquals(
                        List.of(new ClockInterval(56, 84, 1.0)),
                        openResult.clock().get(0)),
                () -> assertEquals(40, openResult.clock().size()),
                () -> assertEquals(SilenceState.NOT_A_BREAK, notABreak.state(), "28 days with no return wave"),
                () -> assertNull(notABreak.endDay()),
                () -> assertEquals(84, notABreak.coverEnd(), "start + 28"),
                () -> assertEquals(Map.of(), closed.clock(), "the silence counts in full from its first day"),
                () -> assertEquals(SilenceState.NOT_A_BREAK, tooFew.state(), "11 of 40 is under 30%"),
                () -> assertEquals(SilenceState.PROVEN_BREAK, wave.state(), "13 of 40 came back"),
                () -> assertEquals(72, wave.endDay(), "the median first return day"),
                () -> assertEquals(72, wave.coverEnd()),
                () -> assertEquals(
                        List.of(new ClockInterval(56, 72, 1.0)), proven.clock().get(0)),
                () -> assertEquals(SilenceState.PROVEN_BREAK, even.state(), "12 of 40 is 30%"),
                () -> assertEquals(72, even.endDay(), "72.5 rounds half to even"));
    }

    @Test
    void panel_isRebuiltEachWeek_andLeavesOutIdLossAndIdChangeIds() {
        Scenario s = new Scenario(111);
        for (int u = 0; u < 120; u++) {
            int from = u < 30 || u >= 100 ? 56 : u <= 50 ? 98 : Integer.MAX_VALUE;
            s.id(d -> d < from);
        }
        for (int u = 100; u < 110; u++) s.lost(u);
        for (int u = 110; u < 120; u++) s.cannotFollow(u);

        SilenceResult result = s.find();

        assertEquals(2, result.events().size());
        SilenceEvent first = result.events().get(0);
        SilenceEvent second = result.events().get(1);
        assertAll(
                () -> assertEquals(100, first.panel(), "IDs of V and IDs that cannot be followed are left out"),
                () -> assertEquals(0.30, first.i(), 1e-12),
                () -> assertEquals(range(0, 30), first.members()),
                () -> assertEquals(SilenceState.NOT_A_BREAK, first.state()),
                () -> assertEquals(70, second.panel(), "the 30 IDs silent since week 8 left the panel"),
                () -> assertEquals(0.30, second.i(), 1e-12, "21 of 70; a frozen panel reads 51 of 100"),
                () -> assertEquals(98, second.startDay()),
                () -> assertEquals(range(30, 51), second.members()),
                () -> assertEquals(SilenceState.OPEN, second.state()),
                () -> assertEquals(new TreeSet<>(range(30, 51)), result.clock().keySet()));
    }

    @Test
    void rhoHit() {
        Scenario s = new Scenario(69);
        for (int u = 0; u < 100; u++) {
            boolean silent = u < 4 || (u >= 20 && u < 52);
            s.id(d -> !silent || d < 56);
            if (u < 10) s.hit("standing", true, u, 40);
            else if (u < 20) s.hit("old", false, u, 5, 40);
            else s.hit("fresh", false, u, 40);
        }

        SilenceResult result = s.find();

        SilenceEvent e = result.events().getFirst();
        assertAll(
                () -> assertEquals(0.35, SharedSilence.rhoHit(0.7, 20, 0.10, 20, 0.20), 1e-12),
                () -> assertEquals(0.7, SharedSilence.rhoHit(0.7, 20, 0.10, 14, 0.20), 1e-12, "14 hit IDs"),
                () -> assertEquals(0.7, SharedSilence.rhoHit(0.7, 14, 0.10, 20, 0.20), 1e-12, "14 free IDs"),
                () -> assertEquals(0.7, SharedSilence.rhoHit(0.7, 20, 0.30, 20, 0.20), 1e-12, "never above rho"),
                () -> assertEquals(0.0, SharedSilence.rhoHit(0.7, 20, -0.05, 20, 0.20), 1e-12, "no free excess"),
                () -> assertEquals(100, e.panel(), "20 free IDs are under 30, so the test runs on the panel"),
                () -> assertEquals(0.36, e.i(), 1e-12),
                () -> assertEquals(1.0, e.rho(), 1e-12),
                () -> assertEquals(
                        0.5,
                        e.rhoHit(),
                        1e-12,
                        "x_free 0.2 of 20, x_hit 0.4 of 80; an always-present issue and an old first hit are not"
                                + " fresh"),
                () -> assertEquals(
                        List.of(new ClockInterval(56, 70, 0.5)), result.clock().get(20)),
                () -> assertEquals(
                        List.of(new ClockInterval(56, 70, 1.0)), result.clock().get(0)));
    }

    @Test
    void evidenceAgainstBreak() {
        SilenceResult ended = traffic(6, RouteClass.S, false).find();
        SilenceEvent kept = traffic(10, RouteClass.S, false).find().events().getFirst();
        SilenceEvent lenient = traffic(6, RouteClass.B, true).find().events().getFirst();

        SilenceEvent e = ended.events().getFirst();
        assertAll(
                () -> assertEquals(SilenceState.TRAFFIC_ENDED, e.state(), "42 a week against 280 is 15%"),
                () -> assertNull(e.endDay()),
                () -> assertEquals(84, e.coverEnd()),
                () -> assertEquals(Map.of(), ended.clock()),
                () -> assertEquals(SilenceState.OPEN, kept.state(), "70 a week is 25%; paced traffic is not read"),
                () -> assertEquals(SilenceState.OPEN, lenient.state(), "in lenient mode B routes are paced"));
    }

    /** The bug: a wave that starts in the 7 days that end at D waits a week for the next whole-week test. */
    @Test
    void provisionalWindow_opensAnEvent_inTheLast7Days() {
        Scenario s = new Scenario(69);
        for (int u = 0; u < 100; u++) {
            boolean silent = u < 40;
            s.id(d -> !silent || d < 63);
        }

        SilenceResult result = s.find();

        SilenceEvent e = result.events().getFirst();
        assertAll(
                () -> assertEquals(1, result.events().size(), "no whole week up to week 8 has a silent ID"),
                () -> assertTrue(e.provisional(), "days 63 to 69 are the window that ends at D, not a whole week"),
                () -> assertEquals(new Scope(ScopeKind.APP, null), e.scope()),
                () -> assertEquals(100, e.panel()),
                () -> assertEquals(0.4, e.i(), 1e-12),
                () -> assertEquals(0.0, e.i0(), 1e-12, "no ID is silent in the shifted blocks before"),
                () -> assertEquals(63, e.startDay(), "the median last series day 62, plus 1"),
                () -> assertEquals(range(0, 40), e.members(), "silent for the 7 days to D"),
                () -> assertEquals(SilenceState.OPEN, e.state(), "6 days in, too early for the traffic test"),
                () -> assertEquals(70, e.coverEnd()),
                () -> assertEquals(
                        List.of(new ClockInterval(63, 70, 1.0)), result.clock().get(0)));
    }

    /** The bug: a shape group that is not the whole app is no scope, so a wave inside it is lost in the app's share. */
    @Test
    void shapeGroup_ofAtLeast20Ids_isAScope() {
        Scenario s = new Scenario(69);
        for (int u = 0; u < 100; u++) {
            boolean silent = u < 10;
            s.id(d -> !silent || d < 56);
            if (u < 40) s.group(u, "shape-a");
        }

        SilenceResult result = s.find();

        SilenceEvent e = result.events().getFirst();
        assertAll(
                () -> assertEquals(1, result.events().size(), "10 of 100 is an excess of 0.10 in the app, under 0.15"),
                () -> assertEquals(new Scope(ScopeKind.SHAPE, "shape-a"), e.scope()),
                () -> assertEquals(40, e.panel()),
                () -> assertEquals(0.25, e.i(), 1e-12, "10 of the group's 40"),
                () -> assertEquals(56, e.startDay()),
                () -> assertEquals(range(0, 10), e.members()));
    }

    private static SilenceResult clusterOf(int clusterIds) {
        Scenario s = new Scenario(69);
        for (int u = 0; u < 29; u++) {
            int stop = u < 10 ? 56 : Integer.MAX_VALUE;
            s.id(d -> d < stop);
            if (u < clusterIds) s.account(u, 7);
        }
        return s.find();
    }

    private static Scenario waveOf40(int asOfDay, int... returnDays) {
        Scenario s = new Scenario(asOfDay);
        for (int u = 0; u < 100; u++) {
            int back = u < returnDays.length ? returnDays[u] : Integer.MAX_VALUE;
            boolean silent = u < 40;
            s.id(d -> !silent || d < 56 || d >= back);
        }
        return s;
    }

    private static Scenario traffic(int stillSending, RouteClass otherClass, boolean lenient) {
        Scenario s = new Scenario(76).routes(otherClass, lenient);
        for (int u = 0; u < 100; u++) {
            boolean silent = u < 40;
            s.id(d -> !silent || d < 56);
            if (!silent) continue;
            for (int d = 28; d < 56; d++) s.row(u, d, PACED, 1).row(u, d, OTHER, 1);
            if (u < stillSending) {
                for (int d = 56; d < 70; d++) s.row(u, d, OTHER, 1);
            }
        }
        return s;
    }

    private static List<Integer> range(int from, int to) {
        return IntStream.range(from, to).boxed().toList();
    }

    private static final class Scenario {

        private static final RouteStatistics STATISTICS =
                new RouteStatistics(0, 0, 0, 0, 0, 0, 0, null, null, null, 0, null);

        private final int asOfDay;
        private final Map<Integer, List<Integer>> days = new TreeMap<>();
        private final Map<Integer, Integer> accountOf = new TreeMap<>();
        private final Map<Integer, String> groupOf = new TreeMap<>();
        private final List<int[]> rows = new ArrayList<>();
        private final Map<String, Map<Integer, List<Integer>>> hits = new TreeMap<>();
        private final Map<String, Boolean> alwaysPresent = new TreeMap<>();
        private final Map<Integer, Double> lost = new TreeMap<>();
        private final Set<Integer> cannotFollow = new TreeSet<>();
        private RouteClass otherClass = RouteClass.S;
        private boolean lenient;
        private PageState page = PageState.OK;

        Scenario(int asOfDay) {
            this.asOfDay = asOfDay;
        }

        /** An ID that has a series day on each day from 0 to {@code D} that {@code active} accepts. */
        void id(IntPredicate active) {
            days.put(
                    days.size(),
                    IntStream.rangeClosed(0, asOfDay).filter(active).boxed().toList());
        }

        void account(int u, int cluster) {
            accountOf.put(u, cluster);
        }

        void group(int u, String key) {
            groupOf.put(u, key);
        }

        void lost(int u) {
            lost.put(u, 1.0);
        }

        void cannotFollow(int u) {
            cannotFollow.add(u);
        }

        void hit(String lineage, boolean present, int u, int... hitDays) {
            alwaysPresent.put(lineage, present);
            hits.computeIfAbsent(lineage, k -> new TreeMap<>())
                    .put(u, IntStream.of(hitDays).boxed().toList());
        }

        Scenario row(int u, int day, int routeNo, int traces) {
            rows.add(new int[] {u, day, routeNo, traces});
            return this;
        }

        Scenario routes(RouteClass other, boolean lenientMode) {
            otherClass = other;
            lenient = lenientMode;
            return this;
        }

        Scenario page(PageState state) {
            page = state;
            return this;
        }

        SilenceResult find() {
            return SharedSilence.find(facts(), series(), identity(), channel(), hitSet(), pageState());
        }

        private Facts facts() {
            List<int[]> sorted = new ArrayList<>(rows);
            sorted.sort(Comparator.<int[]>comparingInt(r -> r[0])
                    .thenComparingInt(r -> r[1])
                    .thenComparingInt(r -> r[2]));
            int n = sorted.size();
            int[][] col = new int[4][n];
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < 4; c++) col[c][i] = sorted.get(i)[c];
            }
            IdDays idDays = new IdDays(
                    col[0],
                    col[1],
                    col[2],
                    new int[n],
                    col[3],
                    new int[n],
                    new int[n],
                    new int[n],
                    new long[n],
                    new int[n],
                    new int[n][0],
                    new long[n][0]);
            List<IdInfo> ids = new ArrayList<>();
            days.forEach(
                    (u, ds) -> ids.add(new IdInfo("u", 10, accountOf.get(u), ds.getFirst(), ds.getLast(), 1, false)));
            RouteClass paced = RouteClass.A;
            return new Facts(
                    asOfDay,
                    0,
                    null,
                    ids,
                    idDays,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new RouteClasses(
                            Map.of(
                                    PACED,
                                    new Route(paced, null, STATISTICS, false),
                                    OTHER,
                                    new Route(otherClass, null, STATISTICS, false)),
                            lenient),
                    List.of(),
                    List.of(),
                    List.of());
        }

        private Series series() {
            Map<Integer, IdSeries> byId = new TreeMap<>();
            days.forEach((u, ds) -> byId.put(
                    u,
                    new IdSeries(
                            ds.getFirst(),
                            ds,
                            ActivityBasis.HUMAN,
                            ds,
                            List.of(),
                            List.of(),
                            ds.getFirst() <= 1,
                            null)));
            return new Series(0, byId);
        }

        private Identity identity() {
            Map<Integer, IdIdentity> byId = new TreeMap<>();
            for (int u : days.keySet()) {
                byId.put(
                        u,
                        new IdIdentity(
                                false,
                                false,
                                false,
                                false,
                                false,
                                groupOf.getOrDefault(u, "(app)"),
                                accountOf.get(u),
                                true,
                                null));
            }
            return new Identity(
                    byId,
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

        private IdChannelResult channel() {
            IdLoss idLoss = lost.isEmpty()
                    ? null
                    : new IdLoss(List.of("app"), 56, false, 1.0, 1.0, lost, 0.0, List.of(1), 0.3, 0.0);
            return new IdChannelResult(idLoss, null, Map.of(), cannotFollow, Set.of());
        }

        private HitSet hitSet() {
            Map<String, LineageHits> byLineage = new TreeMap<>();
            hits.forEach((lineage, hitDays) -> byLineage.put(
                    lineage, new LineageHits(hitDays, 5, 40, List.of(PACED), true, alwaysPresent.get(lineage))));
            return new HitSet(byLineage);
        }

        private PageStateResult pageState() {
            return new PageStateResult(page, page, null, Map.of(), false, List.of());
        }
    }
}
