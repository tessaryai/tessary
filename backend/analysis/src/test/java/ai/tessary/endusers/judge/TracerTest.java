// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.GroupStats;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.RouteClasses.Route;
import ai.tessary.endusers.judge.model.RouteClasses.RouteStatistics;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.judge.model.TracerResult;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.RouteClass;
import ai.tessary.endusers.model.TracerVerdict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The tracer test finds IDs that are replaced over time (spec 3.6). A key that many IDs share, or a chance repeat, read
 * as a job makes a stable app look renamed and refuses its page; a job that went on under a new ID and was missed lets a
 * renamed app count every rename as a lost user.
 */
class TracerTest {

    private static final int S = 1;
    private static final int A = 2;
    private static final long JOB = (12_345L << 7) | 36;

    @Test
    void tracerVerdict_table() {
        assertAll(
                () -> assertEquals(TracerVerdict.IDS_REPLACED, Tracer.verdict(35, 34)),
                () -> assertEquals(0.882, Stats.wilsonLow(34, 35, 1.64), 0.0005, "lower limit at or above 0.30"),
                () -> assertEquals(TracerVerdict.SOME_IDS_REPLACED, Tracer.verdict(20, 3), "0.15 >= 0.10"),
                () -> assertEquals(0.062, Stats.wilsonLow(3, 20, 1.64), 0.0005, "lower limit under 0.30"),
                () -> assertEquals(TracerVerdict.NO_CHANGE_SEEN, Tracer.verdict(19, 1)),
                () -> assertEquals(TracerVerdict.CANNOT_TEST, Tracer.verdict(5, 0)),
                () -> assertEquals(
                        TracerVerdict.SOME_IDS_REPLACED, Tracer.verdict(5, 3), "the some row has no gate on 8 endings"),
                () -> assertEquals(TracerVerdict.CANNOT_TEST, Tracer.verdict(7, 2), "2 continued is under 3"));
    }

    @Test
    void tracer_exclusiveKeyOnly() {
        Scenario base = new Scenario(100);
        base.id().job(JOB, 40, 41, 42);
        base.id().job(JOB, 50, 51, 52, 90);

        Scenario overlap = new Scenario(100);
        overlap.id().job(JOB, 40, 41, 42);
        overlap.id().job(JOB, 50, 51, 52, 90);
        overlap.id().job(JOB, 41, 60, 61);

        Scenario twoDayBystander = new Scenario(100);
        twoDayBystander.id().job(JOB, 40, 41, 42);
        twoDayBystander.id().job(JOB, 50, 51, 52, 90);
        twoDayBystander.id().job(JOB, 41, 60);

        Scenario twoDayOwner = new Scenario(100);
        twoDayOwner.id().job(JOB, 41, 42);
        twoDayOwner.id().job(JOB, 50, 51, 52, 90);

        Scenario twoDaySuccessor = new Scenario(100);
        twoDaySuccessor.id().job(JOB, 40, 41, 42);
        twoDaySuccessor.id().job(JOB, 50, 51).trace(90);

        Scenario notScheduled = new Scenario(100);
        notScheduled.id().jobOn(A, JOB, 40, 41, 42);
        notScheduled.id().jobOn(A, JOB, 50, 51, 52, 90);

        assertAll(
                () -> assertCounts(1, 1, base.test(), "one owner after the other: the job went on"),
                () -> assertCounts(0, 0, overlap.test(), "two owners with overlapping ranges: a template"),
                () -> assertCounts(1, 1, twoDayBystander.test(), "an ID with the key on 2 days holds no job"),
                () -> assertCounts(0, 0, twoDayOwner.test(), "a key on 2 days is not a tracer"),
                () -> assertCounts(1, 0, twoDaySuccessor.test(), "the successor needs the key on 3 days"),
                () -> assertCounts(0, 0, notScheduled.test(), "only class S routes carry jobs"));
    }

    @Test
    void tracer_endsAndContinuations_useTheirWindows() {
        Scenario slackHeld = new Scenario(100);
        slackHeld.id().job(JOB, 40, 41, 42).trace(49);
        slackHeld.id().job(JOB, 55, 56, 57, 90);

        Scenario slackPassed = new Scenario(100);
        slackPassed.id().job(JOB, 40, 41, 42).trace(50);
        slackPassed.id().job(JOB, 55, 56, 57, 90);

        Scenario lookAheadHeld = new Scenario(100);
        lookAheadHeld.id().job(JOB, 40, 41, 42);
        lookAheadHeld.id().job(JOB, 63, 64, 65, 90);

        Scenario lookAheadPassed = new Scenario(100);
        lookAheadPassed.id().job(JOB, 40, 41, 42);
        lookAheadPassed.id().job(JOB, 64, 65, 66, 90);

        Scenario olderSuccessor = new Scenario(100);
        olderSuccessor.id().job(JOB, 40, 41, 42);
        olderSuccessor.id().trace(30).job(JOB, 50, 51, 52, 90);

        Scenario lastEndDay = new Scenario(100);
        lastEndDay.id().job(JOB, 77, 78, 79);

        Scenario tooRecent = new Scenario(100);
        tooRecent.id().job(JOB, 78, 79, 80);

        assertAll(
                () -> assertCounts(1, 1, slackHeld.test(), "a last trace 7 days after the job still ends the ID"),
                () -> assertCounts(0, 0, slackPassed.test(), "a trace 8 days after the job: the ID did not end"),
                () -> assertCounts(1, 1, lookAheadHeld.test(), "the successor starts 21 days after the end"),
                () -> assertCounts(1, 0, lookAheadPassed.test(), "22 days after the end is too late"),
                () -> assertCounts(1, 0, olderSuccessor.test(), "a successor must be first seen after the owner"),
                () -> assertCounts(1, 0, lastEndDay.test(), "a job that ends on D - 21 can be read"),
                () -> assertCounts(0, 0, tooRecent.test(), "a job that ends after D - 21 cannot"));
    }

    @Test
    void tracer_someReplaced_setsTheRangeAndTheIdsBornAfter() {
        Scenario s = new Scenario(100);
        List<Integer> successors = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long job = JOB + (1L << 7) * i;
            s.id().job(job, 40, 41, 42);
            if (i < 3) successors.add(s.id().job(job, 50 + i, 51 + i, 52 + i, 90).u);
        }
        s.id().trace(49);
        int bornOnFirstContinuedDay = s.id().trace(50).u;

        TracerResult result = s.test();

        Set<Integer> born = new TreeSet<>(successors);
        born.add(bornOnFirstContinuedDay);
        assertAll(
                () -> assertEquals(TracerVerdict.SOME_IDS_REPLACED, result.verdict(), "3 of 4: 0.75 >= 0.10"),
                () -> assertCounts(4, 3, result, "every ending is read"),
                () -> assertEquals(false, result.recentWindow(), "no ending in the last 56 days"),
                () -> assertEquals(50, result.firstContinuedDay(), "the first key day of the first successor"),
                () -> assertEquals(1 - Stats.wilsonUp(3, 4, 1.64), result.replacedLow(), 1e-12),
                () -> assertEquals(born, result.bornAfterContinued(), "first seen on or after day 50"));
    }

    @Test
    void tracer_otherVerdicts_leaveTheRangeAndBornIdsEmpty() {
        Scenario s = new Scenario(100);
        s.id().job(JOB, 40, 41, 42);
        s.id().job(JOB, 50, 51, 52, 90);

        TracerResult result = s.test();

        assertAll(
                () -> assertEquals(TracerVerdict.CANNOT_TEST, result.verdict()),
                () -> assertNull(result.firstContinuedDay()),
                () -> assertNull(result.replacedLow()),
                () -> assertEquals(Set.of(), result.bornAfterContinued()));
    }

    @Test
    void tracer_usesTheLast56Days_whenTheyHold8Endings() {
        Scenario s = new Scenario(150);
        for (int i = 0; i < 8; i++) {
            long job = JOB + (1L << 7) * i;
            s.id().job(job, 20, 21, 22);
            s.id().job(job, 30, 31, 32, 140);
        }
        for (int i = 8; i < 16; i++) s.id().job(JOB + (1L << 7) * i, 92, 93, 94);

        Scenario sevenRecent = new Scenario(150);
        for (int i = 0; i < 8; i++) {
            long job = JOB + (1L << 7) * i;
            sevenRecent.id().job(job, 20, 21, 22);
            sevenRecent.id().job(job, 30, 31, 32, 140);
        }
        for (int i = 8; i < 15; i++) sevenRecent.id().job(JOB + (1L << 7) * i, 92, 93, 94);

        TracerResult recent = s.test();
        TracerResult all = sevenRecent.test();

        assertAll(
                () -> assertEquals(true, recent.recentWindow(), "endings on day 94 = D - 56 count as recent"),
                () -> assertCounts(8, 0, recent, "the older replaced endings are left out"),
                () -> assertEquals(TracerVerdict.NO_CHANGE_SEEN, recent.verdict()),
                () -> assertEquals(false, all.recentWindow(), "7 recent endings are too few"),
                () -> assertCounts(15, 8, all, "all data"),
                () -> assertEquals(TracerVerdict.IDS_REPLACED, all.verdict(), "wilson low of 8 in 15 is 0.33"));
    }

    @Test
    void tracer_readsAliasMembersUnderTheirRepresentative() {
        Scenario s = new Scenario(100);
        Scenario.Id owner = s.id().job(JOB, 40, 41);
        s.id().job(JOB, 42).aliasOf(owner);
        s.id().job(JOB, 50, 51, 52, 90);

        assertCounts(1, 1, s.test(), "the member's key day is the owner's third");
    }

    private static void assertCounts(int ended, int continued, TracerResult result, String message) {
        assertEquals(List.of(ended, continued), List.of(result.ended(), result.continued()), message);
    }

    private static final class Scenario {

        private static final RouteStatistics STATISTICS =
                new RouteStatistics(0, 0, 0, 0, 0, 0, 0, null, null, null, 0, null);

        private final int asOfDay;
        private final List<Row> rows = new ArrayList<>();
        private final List<Id> ids = new ArrayList<>();
        private final Map<Integer, Integer> aliasOf = new TreeMap<>();

        Scenario(int asOfDay) {
            this.asOfDay = asOfDay;
        }

        Id id() {
            Id id = new Id(ids.size());
            ids.add(id);
            return id;
        }

        TracerResult test() {
            return Tracer.test(facts(), series(), identity());
        }

        private Facts facts() {
            List<Row> sorted = new ArrayList<>(rows);
            sorted.sort(
                    Comparator.comparingInt(Row::u).thenComparingInt(Row::day).thenComparingInt(Row::route));
            int n = sorted.size();
            int[] id = new int[n];
            int[] day = new int[n];
            int[] route = new int[n];
            int[] one = new int[n];
            long[][] jobs = new long[n][];
            for (int i = 0; i < n; i++) {
                Row r = sorted.get(i);
                id[i] = r.u();
                day[i] = r.day();
                route[i] = r.route();
                one[i] = 1;
                jobs[i] = r.job() == null ? new long[0] : new long[] {r.job()};
            }
            IdDays idDays = new IdDays(
                    id,
                    day,
                    route,
                    one,
                    one,
                    one,
                    new int[n],
                    new int[n],
                    new long[n],
                    new int[n],
                    new int[n][0],
                    jobs);
            List<IdInfo> infos = ids.stream()
                    .map(x -> new IdInfo("u", 10, null, x.first(), x.last(), 1, false))
                    .toList();
            return new Facts(
                    asOfDay,
                    0,
                    null,
                    infos,
                    idDays,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new RouteClasses(
                            Map.of(
                                    S, new Route(RouteClass.S, null, STATISTICS, false),
                                    A, new Route(RouteClass.A, null, STATISTICS, false)),
                            false),
                    List.of(),
                    List.of(),
                    List.of());
        }

        private Series series() {
            Map<Integer, TreeSet<Integer>> days = new TreeMap<>();
            for (Row r : rows) {
                days.computeIfAbsent(aliasOf.getOrDefault(r.u(), r.u()), k -> new TreeSet<>())
                        .add(r.day());
            }
            Map<Integer, IdSeries> byId = new TreeMap<>();
            days.forEach((u, ds) -> {
                List<Integer> list = List.copyOf(ds);
                byId.put(
                        u,
                        new IdSeries(ds.first(), list, ActivityBasis.HUMAN, list, List.of(), List.of(), false, null));
            });
            return new Series(0, byId);
        }

        private Identity identity() {
            return new Identity(
                    Map.of(),
                    aliasOf,
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

        private record Row(int u, int day, int route, Long job) {}

        final class Id {

            final int u;
            private int first = Integer.MAX_VALUE;
            private int last = Integer.MIN_VALUE;

            Id(int u) {
                this.u = u;
            }

            Id job(long job, int... days) {
                return jobOn(S, job, days);
            }

            Id jobOn(int route, long job, int... days) {
                for (int d : days) add(new Row(u, d, route, job));
                return this;
            }

            Id trace(int day) {
                add(new Row(u, day, A, null));
                return this;
            }

            Id aliasOf(Id representative) {
                Scenario.this.aliasOf.put(u, representative.u);
                return this;
            }

            int first() {
                return first;
            }

            int last() {
                return last;
            }

            private void add(Row row) {
                rows.add(row);
                first = Math.min(first, row.day());
                last = Math.max(last, row.day());
            }
        }
    }
}
