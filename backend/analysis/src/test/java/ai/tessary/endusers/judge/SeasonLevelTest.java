// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.judge.SeasonLevel.Observation;
import ai.tessary.endusers.judge.SeasonLevel.Season;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.HitSet;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.GroupStats;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.RouteClasses.Route;
import ai.tessary.endusers.judge.model.RouteClasses.RouteStatistics;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.SilenceResult.ClockInterval;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.RouteClass;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.Stratum;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The season level {@code mu} of spec 7.1 steps 1 to 6: each tested ID's recent use {@code r} against {@code E} from its
 * own earlier rate, and the median of {@code r / E} over IDs no fresh issue touched. A baseline that runs into the
 * recent window, a unit that ignores hour slots for daily IDs, held days left in the window lengths, or freshly hit IDs
 * in the median would print a season that is not there.
 */
class SeasonLevelTest {

    private static final int D = 700;
    private static final int SINCE = 679;
    private static final List<Integer> WEEKLY =
            IntStream.iterate(560, d -> d <= 693, d -> d + 7).boxed().toList();
    private static final List<Integer> FADING =
            concat(IntStream.iterate(560, d -> d <= 651, d -> d + 7).boxed().toList(), List.of(658, 672, 686));
    private static final int PACED = 1;
    private static final int AUTOMATION = 2;
    private static final double LOW = 3 / ((5 + 0.5) / (35 / 7.0 + 0.25) * 42 / 7);

    @Test
    void seasonLevel() {
        List<Double> median072 = concat(Collections.nCopies(15, 0.60), Collections.nCopies(15, 0.84));
        List<Double> median090 = concat(Collections.nCopies(15, 0.80), Collections.nCopies(15, 1.00));
        List<Double> median030 = Collections.nCopies(30, 0.30);
        Season s072 = SeasonLevel.fromFree(median072, SINCE);
        Season s090 = SeasonLevel.fromFree(median090, SINCE);
        Season s029 = SeasonLevel.fromFree(median072.subList(0, 29), SINCE);
        Season s030 = SeasonLevel.fromFree(median030, SINCE);
        assertAll(
                () -> assertEquals(0.72, s072.mu(), 1e-12),
                () -> assertEquals(0.72, s072.median(), 1e-12),
                () -> assertTrue(s072.banner(), "0.72 prints the banner"),
                () -> assertEquals(new Season(1.0, 0.9, 30, SINCE), s090, "0.90 is at least 0.85"),
                () -> assertFalse(s090.banner()),
                () -> assertEquals(new Season(1.0, null, 29, SINCE), s029, "29 free IDs are too few"),
                () -> assertFalse(s029.banner()),
                () -> assertEquals(0.4, s030.mu(), 1e-12, "clamped to 0.4"),
                () -> assertTrue(s030.banner()));
    }

    @Test
    void observe_dayUnit_baselineAndRecentWindows() {
        IdSeries weekly = series(WEEKLY);
        double lam = (5 + 0.5) / (35 / 7.0 + 0.25);
        assertAll(
                () -> assertEquals(
                        new Observation(6, lam * 42 / 7),
                        SeasonLevel.observe(D, weekly, new int[0], List.of()),
                        "n = 6"),
                () -> assertEquals(
                        new Observation(6, lam * 28 / 7),
                        SeasonLevel.observe(D, weekly, new int[0], List.of(new ClockInterval(658, 672, 0.8))),
                        "held days leave the recent window length"),
                () -> assertNull(
                        SeasonLevel.observe(D, weekly, new int[0], List.of(new ClockInterval(567, 590, 0.8))),
                        "under 3 baseline weeks are left"),
                () -> assertNull(
                        SeasonLevel.observe(D, series(WEEKLY.subList(0, 14)), new int[0], List.of()),
                        "no series day in the recent window: on the stop track"),
                () -> assertNull(
                        SeasonLevel.observe(D, series(WEEKLY.subList(15, 20)), new int[0], List.of()),
                        "fewer than 6 series days"));
    }

    @Test
    void observe_dailyId_countsHourSlots() {
        List<Integer> daily = IntStream.range(560, 700).boxed().toList();
        int[] slots = daily.stream()
                .flatMapToInt(d -> IntStream.of(d * 24 + 9, d * 24 + 20))
                .toArray();
        assertAll(
                () -> assertEquals(
                        new Observation(28, (126 + 0.5) / (63 / 7.0 + 0.25) * 2),
                        SeasonLevel.observe(D, series(daily), slots, List.of()),
                        "7 days a week: hour slots, n = 2"),
                () -> assertEquals(
                        new Observation(14, (63 + 0.5) / (63 / 7.0 + 0.25) * 2),
                        SeasonLevel.observe(D, series(daily), new int[0], List.of()),
                        "no hour slot: days"));
    }

    @Test
    void observe_leavesOutIdsThatAreNotTested() {
        List<Integer> daily = IntStream.range(560, 700).boxed().toList();
        assertAll(
                () -> assertNull(
                        SeasonLevel.observe(D, series(List.of(670, 675, 680, 685, 690, 695)), new int[0], List.of()),
                        "30 days old"),
                () -> assertNull(
                        SeasonLevel.observe(D, series(List.of(560, 600, 640, 660, 680, 700)), new int[0], List.of()),
                        "5 series days before G"),
                () -> assertNull(
                        SeasonLevel.observe(
                                D, series(List.of(560, 600, 640, 650, 660, 680, 690)), new int[0], List.of()),
                        "E = 1.5 / 5.25 * 6 is under 2"),
                () -> assertNull(
                        SeasonLevel.observe(D, series(daily), new int[] {700 * 24 + 9}, List.of()),
                        "no hour slot before G"));
    }

    @Test
    void level_readsHourSlotsOfPacedRoutesFromTheFacts() {
        Fixture f = new Fixture();
        List<Integer> daily = IntStream.range(560, 700).boxed().toList();
        for (int u = 0; u < 30; u++) f.id(u, daily, 0.1, null);
        double expected = (126 + 0.5) / (63 / 7.0 + 0.25) * 14 / 7;
        assertEquals(
                new Season(1.0, 28 / expected, 30, SINCE),
                f.level(new HitSet(Map.of())),
                "hours 9 and 20 on the paced route; hour 3 on the automation route is not a slot");
    }

    @Test
    void level_freeIdsLeaveOutFreshHitsOfIssuesNotAlwaysPresent() {
        Fixture f = new Fixture();
        for (int u = 0; u < 29; u++) f.id(u, FADING, 0.1, null);
        f.id(29, WEEKLY, 0.1, null);
        f.id(30, FADING, 0.3, null);
        f.id(31, FADING, 0.1, SetAsideReason.SERVICE);
        f.id(32, FADING, 0.1, null);
        f.cannotFollow = Set.of(32);
        assertAll(
                () -> assertEquals(
                        new Season(1.0, null, 29, SINCE),
                        f.level(hit(29, 690, false)),
                        "a fresh hit leaves the ID out; p_stop 0.3, set-aside and cannot-follow IDs are not tested"),
                () -> assertEquals(
                        new Season(LOW, LOW, 30, SINCE), f.level(hit(29, 690, true)), "an always-present issue"),
                () -> assertEquals(
                        new Season(LOW, LOW, 30, SINCE), f.level(hit(29, 643, false)), "a hit 57 days before D"));
    }

    private static HitSet hit(int u, int day, boolean alwaysPresent) {
        return new HitSet(
                Map.of("lineage", new LineageHits(Map.of(u, List.of(day)), day, day, List.of(), true, alwaysPresent)));
    }

    private static IdSeries series(List<Integer> days) {
        return new IdSeries(days.getFirst(), days, ActivityBasis.HUMAN, days, List.of(), List.of(), false, null);
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    private static final class Fixture {
        final Map<Integer, IdSeries> series = new TreeMap<>();
        final Map<Integer, IdIdentity> identity = new TreeMap<>();
        final Map<Integer, IdOdds> odds = new TreeMap<>();
        Set<Integer> cannotFollow = Set.of();

        void id(int u, List<Integer> days, double pStop, @Nullable SetAsideReason setAside) {
            series.put(u, series(days));
            identity.put(
                    u, new IdIdentity(false, false, false, false, false, "(app)", null, setAside == null, setAside));
            odds.put(
                    u,
                    new IdOdds(
                            Stratum.E,
                            pStop,
                            pStop,
                            pStop,
                            null,
                            false,
                            false,
                            D - days.getLast(),
                            D - days.getLast()));
        }

        Season level(HitSet hits) {
            return SeasonLevel.level(
                    facts(),
                    new Series(560, series),
                    new Identity(
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
                            0.0),
                    new IdChannelResult(null, null, Map.of(), cannotFollow, Set.of()),
                    new SilenceResult(List.of(), Map.of()),
                    new StopOdds(odds, 7.0, 7.0, 21, List.of(), List.of(), null, null, Map.of(), Map.of(), Map.of()),
                    hits);
        }

        /** Each series day has a row on the paced route 1 (hours 9 and 20) and on the automation route 2 (hour 3). */
        private Facts facts() {
            List<int[]> rows = new ArrayList<>();
            series.forEach((u, s) -> s.days().forEach(d -> {
                rows.add(new int[] {u, d, PACED, 1 << 9 | 1 << 20});
                rows.add(new int[] {u, d, AUTOMATION, 1 << 3});
            }));
            int n = rows.size();
            return new Facts(
                    D,
                    560,
                    null,
                    List.of(),
                    new IdDays(
                            rows.stream().mapToInt(r -> r[0]).toArray(),
                            rows.stream().mapToInt(r -> r[1]).toArray(),
                            rows.stream().mapToInt(r -> r[2]).toArray(),
                            new int[n],
                            new int[n],
                            new int[n],
                            new int[n],
                            rows.stream().mapToInt(r -> r[3]).toArray(),
                            new long[n],
                            new int[n],
                            new int[n][0],
                            new long[n][0]),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new RouteClasses(Map.of(PACED, route(RouteClass.A), AUTOMATION, route(RouteClass.S)), false),
                    List.of(),
                    List.of(),
                    List.of());
        }

        private static Route route(RouteClass routeClass) {
            return new Route(
                    routeClass, null, new RouteStatistics(100, 0, 0, 0, 0, 0, 0, null, null, null, 0, null), false);
        }
    }
}
