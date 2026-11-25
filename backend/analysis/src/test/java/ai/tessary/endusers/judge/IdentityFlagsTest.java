// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static ai.tessary.endusers.judge.DaySeriesTest.FactsBuilder.hours;
import static ai.tessary.endusers.judge.DaySeriesTest.FactsBuilder.sessions;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.endusers.judge.DaySeriesTest.FactsBuilder;
import ai.tessary.endusers.judge.IdentityFlags.Flags;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The per-ID flags of spec 3.1 decide which IDs are set aside and whether the page refuses. A size test that reads a
 * timer route, counts hours per route instead of per day, or a service test that ignores its presence or variation cut
 * sets aside real users or keeps keys and machines in the counts.
 */
class IdentityFlagsTest {

    private static final int A = 1;
    private static final int S = 2;
    private static final int B = 3;
    private static final int C = 4;
    private static final int M = 5;
    private static final int[] NONE = new int[0];

    @Test
    void aggregate() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(200));
        int median15 = hoursOnDays(f, B, 14, 15, 16);
        int longDays = hoursOnDays(f, A, 13, 13, 16);
        int never16 = hoursOnDays(f, A, 13, 13, 13);
        int twoDays = hoursOnDays(f, A, 20, 20);
        int timer = hoursOnDays(f, S, 16, 16, 16);
        int split = f.id(100);
        for (int d = 100; d < 103; d++) {
            f.row(split, d, A, 1, hours(8), NONE, NONE).row(split, d, B, 1, hours(16) & ~hours(8), NONE, NONE);
        }
        int sessions20 = f.id(100);
        int sessions19 = f.id(100);
        for (int d = 100; d < 103; d++) {
            f.row(sessions20, d, M, 1, hours(2), NONE, sessions(0, 20));
            f.row(sessions19, d, M, 1, hours(2), NONE, sessions(0, 19));
        }

        Map<Integer, Flags> flags = flags(f.build());

        assertAll(
                () -> assertEquals(true, flags.get(median15).aggregate(), "median 15 hours, on a B route"),
                () -> assertEquals(true, flags.get(longDays).aggregate(), "a third of the days at 16 hours"),
                () -> assertEquals(false, flags.get(never16).aggregate()),
                () -> assertEquals(false, flags.get(twoDays).aggregate(), "two days never do"),
                () -> assertEquals(false, flags.get(timer).aggregate(), "a timer route is not read"),
                () -> assertEquals(true, flags.get(split).aggregate(), "hours of one day add up over routes"),
                () -> assertEquals(true, flags.get(sessions20).aggregate(), "median 20 sessions"),
                () -> assertEquals(false, flags.get(sessions19).aggregate()));
    }

    @Test
    void groupHint_readsPacedRoutesOnly() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(200));
        int onA = f.id(100);
        int onB = f.id(100);
        int sparse = f.id(100);
        for (int d = 100; d < 103; d++) {
            f.row(onA, d, A, 1, hours(2), NONE, sessions(0, 8));
            f.row(onB, d, B, 1, hours(2), NONE, sessions(0, 8));
        }
        f.row(sparse, 100, A, 1, hours(2), NONE, sessions(0, 8))
                .row(sparse, 101, A, 1, hours(2), NONE, sessions(0, 8))
                .row(sparse, 102, A, 1, hours(2), NONE, NONE)
                .row(sparse, 103, A, 1, hours(2), NONE, NONE);
        int hoursOnM = hoursOnDays(f, M, 10, 10, 10);
        int heavy = hoursOnDays(f, A, 14, 14, 14);

        Map<Integer, Flags> flags = flags(f.build());

        assertAll(
                () -> assertEquals(true, flags.get(onA).groupHint(), "median 8 sessions a day on an A route"),
                () -> assertEquals(false, flags.get(onB).groupHint(), "the same on a B route"),
                () -> assertEquals(
                        true, flags.get(sparse).groupHint(), "the session median reads only days with a session"),
                () -> assertEquals(true, flags.get(hoursOnM).groupHint(), "median 10 hours on an M route"),
                () -> assertEquals(false, flags.get(heavy).groupHint(), "an aggregate ID is not also a hint"),
                () -> assertEquals(true, flags.get(heavy).aggregate()));
    }

    @Test
    void service() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(300));
        int steady = dailyCounts(f, C, 14, 9, 11);
        int short13 = f.id(100);
        for (int i = 0; i < 13; i++) f.row(short13, 100 + i, C, i % 2 == 0 ? 9 : 11, hours(1), NONE, NONE);
        int pacedVaried = dailyCounts(f, A, 14, 8, 12);
        int machineVaried = dailyCounts(f, C, 14, 8, 12);
        int lateSetup = f.id(100);
        for (int i = 0; i < 15; i++) {
            if (i == 7) continue;
            f.row(
                    lateSetup,
                    100 + i,
                    S,
                    i % 2 == 0 ? 8 : 12,
                    hours(1),
                    i == 8 ? new int[] {3, 40} : new int[] {3},
                    NONE);
        }

        Map<Integer, Flags> flags = flags(f.build());

        assertAll(
                () -> assertEquals(true, flags.get(steady).service(), "14 days in a life of 15, CV 0.10"),
                () -> assertEquals(false, flags.get(short13).service(), "13 trace days"),
                () -> assertEquals(false, flags.get(pacedVaried).service(), "CV 0.20 with a paced trace"),
                () -> assertEquals(
                        true, flags.get(machineVaried).service(), "CV 0.20, no paced trace, no late set-up day"),
                () -> assertEquals(
                        false, flags.get(lateSetup).service(), "CV 0.20 with a set-up day 8 days after the first"));
    }

    @Test
    void tokenLink_needsEverySessionLinkedAndASeriesUnderSevenDays() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(200));
        int sixDays = f.id(new IdInfo("u", 10, null, 100, 106, 2, true));
        int sevenDays = f.id(new IdInfo("u", 10, null, 100, 107, 2, true));
        int notLinked = f.id(new IdInfo("u", 10, null, 100, 100, 2, false));
        int noSession = f.id(new IdInfo("u", 10, null, 100, 100, 0, true));
        f.day(sixDays, 100, A).day(sixDays, 106, A);
        f.day(sevenDays, 100, A).day(sevenDays, 107, A);
        f.day(notLinked, 100, A);
        f.day(noSession, 100, A);

        Map<Integer, Flags> flags = flags(f.build());

        assertAll(
                () -> assertEquals(true, flags.get(sixDays).tokenLink()),
                () -> assertEquals(false, flags.get(sevenDays).tokenLink()),
                () -> assertEquals(false, flags.get(notLinked).tokenLink()),
                () -> assertEquals(false, flags.get(noSession).tokenLink()));
    }

    @Test
    void oddShape_rareShapeWithMoreThanTenTimesTheMedianTraces() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(200));
        for (int i = 0; i < 29; i++) f.day(f.id(100), 100, A);
        int odd = f.id(new IdInfo("x", 5, null, 100, 100, 1, false));
        int tenTimes = f.id(new IdInfo("z", 3, null, 100, 100, 1, false));
        f.row(odd, 100, C, 11, hours(1), NONE, NONE);
        f.row(tenTimes, 100, C, 10, hours(1), NONE, NONE);

        Map<Integer, Flags> flags = flags(f.build());

        assertAll(
                () -> assertEquals(true, flags.get(odd).oddShape(), "11 traces against a median of 1, all routes"),
                () -> assertEquals(false, flags.get(tenTimes).oddShape(), "10 is not more than 10 times"),
                () -> assertEquals(false, flags.get(0).oddShape(), "a shape of 29 IDs"));
    }

    @Test
    void oddShape_needsThirtyIds() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(200));
        for (int i = 0; i < 28; i++) f.day(f.id(100), 100, A);
        int odd = f.id(new IdInfo("x", 5, null, 100, 100, 1, false));
        f.row(odd, 100, C, 11, hours(1), NONE, NONE);

        assertEquals(false, flags(f.build()).get(odd).oddShape());
    }

    @Test
    void sizeDays_countDaysOnSizeRoutesOnly() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(200));
        int u = f.id(100);
        f.row(u, 100, A, 50, hours(1), NONE, NONE)
                .row(u, 100, B, 50, hours(1), NONE, NONE)
                .day(u, 101, M)
                .day(u, 102, S)
                .day(u, 103, C);

        assertEquals(2, flags(f.build()).get(u).sizeDays(), "ID-days on A, A2, M and B routes, not traces");
    }

    @Test
    void flags_readTheMemberRowsUnderTheRepresentative() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(200));
        int member = f.id(100);
        int rep = f.id(101);
        f.row(member, 100, A, 1, hours(14), NONE, NONE).row(member, 101, A, 1, hours(14), NONE, NONE);
        f.row(rep, 102, A, 1, hours(14), NONE, NONE);
        Facts facts = f.build();
        Map<Integer, Integer> aliasOf = Map.of(member, rep);

        Map<Integer, Flags> flags = IdentityFlags.flags(facts, aliasOf, DaySeries.build(facts, aliasOf));

        assertAll(
                () -> assertEquals(Set.of(rep), flags.keySet()),
                () -> assertEquals(true, flags.get(rep).aggregate()));
    }

    private static Map<Integer, Flags> flags(Facts facts) {
        return IdentityFlags.flags(facts, Map.of(), DaySeries.build(facts, Map.of()));
    }

    private static int hoursOnDays(FactsBuilder f, int routeNo, int... dayHours) {
        int u = f.id(100);
        for (int i = 0; i < dayHours.length; i++) f.row(u, 100 + i, routeNo, 1, hours(dayHours[i]), NONE, NONE);
        return u;
    }

    /** {@code days} trace days in a life of {@code days + 1} (day 7 missing), counts alternating. */
    private static int dailyCounts(FactsBuilder f, int routeNo, int days, int low, int high) {
        int u = f.id(100);
        int written = 0;
        for (int i = 0; written < days; i++) {
            if (i == 7) continue;
            f.row(u, 100 + i, routeNo, written % 2 == 0 ? low : high, hours(1), NONE, NONE);
            written++;
        }
        return u;
    }
}
