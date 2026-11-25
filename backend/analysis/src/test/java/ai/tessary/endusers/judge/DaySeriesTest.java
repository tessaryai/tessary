// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.AliasEdge;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import ai.tessary.endusers.judge.model.Facts.NullDay;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.RouteClasses.Route;
import ai.tessary.endusers.judge.model.RouteClasses.RouteStatistics;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.RouteClass;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The day series is what every later stage reads as an ID's activity: the wrong basis, a set-up day counted on every
 * day of a new job, or a member's days left out of its representative's series moves every silence and every stop.
 */
class DaySeriesTest {

    private static final int A = 1;
    private static final int S = 2;
    private static final int B = 3;
    private static final int C = 4;
    private static final int M = 5;

    @Test
    void series_usesPacedPlusSetupWhenPacedExists() {
        FactsBuilder f = standardRoutes(new FactsBuilder(60));
        int human = f.id(10);
        int unattended = f.id(12);
        int machine = f.id(5);
        f.day(human, 10, A).day(human, 20, A).slots(human, 12, S, 8).slots(human, 30, S, 8);
        f.slots(unattended, 12, S, 8).slots(unattended, 30, S, 8).slots(unattended, 40, S, 20);
        f.day(machine, 5, C).day(machine, 9, C);

        Series series = DaySeries.build(f.build(), Map.of());

        assertAll(
                () -> assertEquals(5, series.t0Id()),
                () -> assertEquals(
                        new IdSeries(
                                10,
                                List.of(10, 12, 20),
                                ActivityBasis.HUMAN,
                                List.of(10, 20),
                                List.of(12, 30),
                                List.of(12),
                                false,
                                18.0),
                        series.byId().get(human),
                        "paced days and set-up days; the automation day 30 is not activity"),
                () -> assertEquals(
                        new IdSeries(
                                12,
                                List.of(12, 30, 40),
                                ActivityBasis.UNATTENDED,
                                List.of(),
                                List.of(12, 30, 40),
                                List.of(12, 40),
                                false,
                                14.0),
                        series.byId().get(unattended),
                        "no paced day: automation days and set-up days"),
                () -> assertEquals(
                        new IdSeries(
                                5, List.of(5, 9), ActivityBasis.MACHINE, List.of(), List.of(), List.of(), true, null),
                        series.byId().get(machine),
                        "neither: every trace day"));
    }

    @Test
    void series_lenientCountsBAndMRoutesAsPaced() {
        FactsBuilder f = new FactsBuilder(60)
                .route(S, RouteClass.S)
                .route(B, RouteClass.B)
                .route(M, RouteClass.M)
                .lenient();
        int u = f.id(10);
        f.day(u, 10, B).day(u, 11, M).slots(u, 12, S, 8);

        IdSeries s = DaySeries.build(f.build(), Map.of()).byId().get(u);

        assertAll(
                () -> assertEquals(ActivityBasis.LENIENT, s.basis()),
                () -> assertEquals(List.of(10, 11), s.pacedDays()),
                () -> assertEquals(List.of(12), s.autoDays(), "only S routes run without the person here"),
                () -> assertEquals(List.of(10, 11, 12), s.days()));
    }

    @Test
    void setupDay_firstSevenDaysOfAPairCountOnce() {
        FactsBuilder f = standardRoutes(new FactsBuilder(60));
        int u = f.id(10);
        f.slots(u, 10, S, 4, 5)
                .slots(u, 16, S, 30)
                .slots(u, 17, S, 70)
                .day(u, 20, B)
                .day(u, 22, B)
                .slots(u, 25, S, 4);

        IdSeries s = DaySeries.build(f.build(), Map.of()).byId().get(u);

        assertAll(
                () -> assertEquals(
                        List.of(10, 17, 20),
                        s.setupDays(),
                        "a pair first seen up to 6 days after the first automation day counts on that day; slot 70"
                                + " (high mask) on day 17 is a new set-up; a B route is one pair whatever the slot;"
                                + " a known pair adds nothing"),
                () -> assertEquals(List.of(10, 16, 17, 20, 22, 25), s.autoDays()));
    }

    @Test
    void leftCensored_neverNew() {
        FactsBuilder f = standardRoutes(new FactsBuilder(200));
        int first = f.id(100);
        int next = f.id(101);
        int later = f.id(102);
        f.day(first, 100, A).day(next, 101, A).day(later, 102, A);

        Series series = DaySeries.build(f.build(), Map.of());

        assertAll(
                () -> assertEquals(100, series.t0Id()),
                () -> assertEquals(true, series.byId().get(first).leftCensored()),
                () -> assertEquals(true, series.byId().get(next).leftCensored(), "first_seen = t0_id + 1"),
                () -> assertEquals(false, series.byId().get(later).leftCensored()));
    }

    @Test
    void firstSeen_isTheStoredFirstDayNotTheFirstDayInTheWindow() {
        FactsBuilder f = standardRoutes(new FactsBuilder(200));
        int u = f.id(20);
        f.day(u, 150, A);

        Series series = DaySeries.build(f.build(), Map.of());

        assertAll(() -> assertEquals(20, series.byId().get(u).firstSeen()), () -> assertEquals(20, series.t0Id()));
    }

    @Test
    void t0Id_isTheHistoryStartWhenNoIdHasATrace() {
        FactsBuilder f = standardRoutes(new FactsBuilder(200)).t0(42);

        Series series = DaySeries.build(f.build(), Map.of());

        assertAll(() -> assertEquals(42, series.t0Id()), () -> assertEquals(Map.of(), series.byId()));
    }

    @Test
    void series_aliasMemberDaysGoToTheRepresentative() {
        FactsBuilder f = standardRoutes(new FactsBuilder(200));
        int member = f.id(10);
        int rep = f.id(12);
        f.day(member, 10, A).day(member, 11, A).day(rep, 12, A);

        Series series = DaySeries.build(f.build(), Map.of(member, rep));

        assertAll(
                () -> assertEquals(List.of(rep), List.copyOf(series.byId().keySet())),
                () -> assertEquals(List.of(10, 11, 12), series.byId().get(rep).days()),
                () -> assertEquals(10, series.byId().get(rep).firstSeen(), "the member's first day"));
    }

    @Test
    void dayRow_onARouteWithNoClassFails() {
        FactsBuilder f = standardRoutes(new FactsBuilder(60));
        int u = f.id(10);
        f.day(u, 10, 99);
        Facts facts = f.build();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> DaySeries.build(facts, Map.of()));
        assertEquals("route 99 has day rows but no class", e.getMessage());
    }

    static FactsBuilder standardRoutes(FactsBuilder f) {
        return f.route(A, RouteClass.A)
                .route(S, RouteClass.S)
                .route(B, RouteClass.B)
                .route(C, RouteClass.C)
                .route(M, RouteClass.M);
    }

    /** Builds {@link Facts} by hand; rows are sorted into the documented order at {@link #build()}. */
    static final class FactsBuilder {

        private static final RouteStatistics STATISTICS =
                new RouteStatistics(0, 0, 0, 0, 0, 0, 0, null, null, null, 0, null);

        private final int asOfDay;
        private int t0;
        private boolean lenient;
        private final Map<Integer, Route> routes = new TreeMap<>();
        private final List<IdInfo> ids = new ArrayList<>();
        private final List<Row> rows = new ArrayList<>();
        private final List<NullDay> nullDays = new ArrayList<>();
        private final List<AliasEdge> aliasEdges = new ArrayList<>();

        FactsBuilder(int asOfDay) {
            this.asOfDay = asOfDay;
        }

        FactsBuilder t0(int day) {
            t0 = day;
            return this;
        }

        FactsBuilder route(int routeNo, RouteClass routeClass) {
            routes.put(routeNo, new Route(routeClass, null, STATISTICS, false));
            return this;
        }

        FactsBuilder lenient() {
            lenient = true;
            return this;
        }

        int id(int firstDay) {
            return id(new IdInfo("u", 10, null, firstDay, firstDay, 1, false));
        }

        int id(IdInfo info) {
            ids.add(info);
            return ids.size() - 1;
        }

        FactsBuilder day(int id, int day, int routeNo) {
            return row(id, day, routeNo, 1, 1 << 9, new int[0], new int[0]);
        }

        FactsBuilder slots(int id, int day, int routeNo, int... slots) {
            return row(id, day, routeNo, 1, 1 << 9, slots, new int[0]);
        }

        FactsBuilder row(int id, int day, int routeNo, int traces, int hourMask, int[] slots, int[] sessions) {
            rows.add(new Row(id, day, routeNo, traces, hourMask, slots, sessions));
            return this;
        }

        FactsBuilder nullDay(int day, int routeNo, int traces, int tracesNoUserId) {
            nullDays.add(new NullDay(day, routeNo, 0, traces, tracesNoUserId));
            return this;
        }

        FactsBuilder alias(AliasEdge edge) {
            aliasEdges.add(edge);
            return this;
        }

        Facts build() {
            List<Row> sorted = new ArrayList<>(rows);
            sorted.sort(
                    Comparator.comparingInt(Row::id).thenComparingInt(Row::day).thenComparingInt(Row::routeNo));
            int n = sorted.size();
            int[] id = new int[n];
            int[] day = new int[n];
            int[] routeNo = new int[n];
            int[] traces = new int[n];
            int[] hourMask = new int[n];
            long[] slotLow = new long[n];
            int[] slotHigh = new int[n];
            int[][] sessions = new int[n][];
            for (int i = 0; i < n; i++) {
                Row r = sorted.get(i);
                id[i] = r.id();
                day[i] = r.day();
                routeNo[i] = r.routeNo();
                traces[i] = r.traces();
                hourMask[i] = r.hourMask();
                for (int s : r.slots()) {
                    if (s < 64) slotLow[i] |= 1L << s;
                    else slotHigh[i] |= 1 << (s - 64);
                }
                sessions[i] = r.sessions();
            }
            IdDays idDays = new IdDays(
                    id,
                    day,
                    routeNo,
                    new int[n],
                    traces,
                    new int[n],
                    new int[n],
                    hourMask,
                    slotLow,
                    slotHigh,
                    sessions,
                    new long[n][0]);
            return new Facts(
                    asOfDay,
                    t0,
                    null,
                    List.copyOf(ids),
                    idDays,
                    List.copyOf(nullDays),
                    List.copyOf(aliasEdges),
                    List.of(),
                    List.of(),
                    List.of(),
                    new RouteClasses(routes, lenient),
                    List.of(),
                    List.of(),
                    List.of());
        }

        static int hours(int count) {
            return (1 << count) - 1;
        }

        static int[] sessions(int from, int count) {
            int[] s = new int[count];
            for (int i = 0; i < count; i++) s[i] = from + i;
            return s;
        }

        private record Row(int id, int day, int routeNo, int traces, int hourMask, int[] slots, int[] sessions) {}
    }
}
