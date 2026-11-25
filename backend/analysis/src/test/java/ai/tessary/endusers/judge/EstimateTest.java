// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.endusers.judge.Cells.Cell;
import ai.tessary.endusers.judge.Cells.Pools;
import ai.tessary.endusers.judge.Estimate.Fit;
import ai.tessary.endusers.judge.Estimate.Group;
import ai.tessary.endusers.judge.Estimate.Numbers;
import ai.tessary.endusers.judge.Trials.Arm;
import ai.tessary.endusers.judge.Trials.Trial;
import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.HitSet;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.IdChannelResult.IdChange;
import ai.tessary.endusers.judge.model.IdChannelResult.IdChangeEvent;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.GroupStats;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.IssueEstimate;
import ai.tessary.endusers.judge.model.IssueEstimate.LineageEstimate;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.Scope;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.ScopeKind;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.Stratum;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The estimate of spec 8.5. A late hit in the estimate, a cannot-follow ID in it, an ID-change week compared, an
 * interval with no floor, a {@code z} that ignores few IDs or few accounts, an account that collides with a key index,
 * or a residual that is not "stops seen minus stops expected" would print a loss the data does not hold.
 */
class EstimateTest {

    private static final Cell CELL = new Cell(5, 4, 2, 0, false, null);
    private static final Cell LONELY = new Cell(1, 0, 0, 0, false, null);

    @Test
    void estimate_numbers() {
        Numbers n = Estimate.numbers(estimate(0.24, 0.30, 0, 0.05, 0.03, 96, 96, 96));
        assertAll(
                () -> assertEquals(0.05, n.seTot(), 1e-12),
                () -> assertEquals(2.6, n.z(), 1e-12, "n_m under 100"),
                () -> assertEquals(23.04, n.lost(), 1e-9),
                () -> assertEquals(10.56, n.low(), 1e-9),
                () -> assertEquals(41.28, n.high(), 1e-9),
                () -> assertEquals(
                        2.0,
                        Estimate.numbers(estimate(0.24, 0.30, 0, 0.05, 0.03, 150, 96, 25))
                                .z()),
                () -> assertEquals(2.0, Estimate.z(100, 20)),
                () -> assertEquals(2.6, Estimate.z(150, 19.9), "acc_eff under 20"),
                () -> assertEquals(2.6, Estimate.z(99, 50)));
    }

    @Test
    void numbers_takeTheNullMeanOff_andNeverGoBelowZero() {
        Numbers shifted = Estimate.numbers(estimate(0.34, 0.40, 0.1, 0.05, 0.03, 96, 96, 96));
        Numbers negative = Estimate.numbers(estimate(-0.2, -0.1, 0, 0.01, 0.01, 200, 100, 30));
        assertAll(
                () -> assertEquals(0.24, shifted.rateLo(), 1e-12),
                () -> assertEquals(0.30, shifted.rateHi(), 1e-12),
                () -> assertEquals(23.04, shifted.lost(), 1e-9),
                () -> assertEquals(0.0, negative.lost()),
                () -> assertEquals(0.0, negative.low()),
                () -> assertEquals(0.0, negative.high(), "-0.1 + 2 x 0.04 is still below zero"));
    }

    @Test
    void seTot_hasFloorTau() {
        assertAll(
                () -> assertEquals(0.04, Estimate.seTot(0.01, 0.02), 1e-12),
                () -> assertEquals(0.06, Estimate.seTot(0.01, 0.06), 1e-12, "the null's spread"),
                () -> assertEquals(0.07, Estimate.seTot(0.07, 0.06), 1e-12));
    }

    @Test
    void fit_isStopsSeenLessStopsExpected() {
        Map<Group, List<Trial>> groups = new LinkedHashMap<>();
        groups.put(new Group(20, CELL), List.of(exposed(10, 0.8, 1), exposed(11, 0, 0)));
        groups.put(new Group(20, LONELY), List.of(exposed(12, 0.5, 1), exposed(10, 0, 0)));
        Pools pools = new Pools(controls());
        Fit apart = Estimate.fit(groups, pools, Map.of());
        Fit together = Estimate.fit(groups, pools, Map.of(10, 1, 11, 1));
        Fit clusterLikeAKey = Estimate.fit(groups, pools, Map.of(10, 11));
        assertNotNull(apart);
        assertNotNull(together);
        assertNotNull(clusterLikeAKey);
        assertAll(
                () -> assertEquals(2, apart.nM()),
                () -> assertEquals(0.2, apart.rateLo(), 1e-12, "(0.8 - 0.2 + 0 - 0.2) / 2"),
                () -> assertEquals(0.3, apart.rateHi(), 1e-12),
                () -> assertEquals(Math.sqrt(0.36 + 0.04 + 4 * 0.16 / 5) / 2, apart.se(), 1e-12),
                () -> assertEquals(2.0, apart.accEff(), 1e-12),
                () -> assertEquals(0.8, apart.sumLo(), 1e-12),
                () -> assertEquals(1.0, apart.sumHi(), 1e-12),
                () -> assertEquals(0.4, apart.expLo(), 1e-12),
                () -> assertEquals(2, apart.trials()),
                () -> assertEquals(1, apart.unmatched(), "ID 12 only; ID 10 has a matched trial"),
                () -> assertEquals(Math.sqrt(0.16 + 4 * 0.16 / 5) / 2, together.se(), 1e-12),
                () -> assertEquals(1.0, together.accEff(), 1e-12),
                () -> assertEquals(apart.se(), clusterLikeAKey.se(), 1e-12, "cluster 11 is not key index 11"),
                () -> assertEquals(2.0, clusterLikeAKey.accEff(), 1e-12));
    }

    @Test
    void fit_poolVarianceHasAFloor() {
        List<Trial> zeros = new ArrayList<>();
        for (int u = 1; u <= 5; u++) zeros.add(new Trial(u, 140, 1, 0, 0, CELL, Arm.UNEXPOSED));
        Fit fit = Estimate.fit(Map.of(new Group(20, CELL), List.of(exposed(10, 0, 0))), new Pools(zeros), Map.of());
        assertNotNull(fit);
        assertEquals(Math.sqrt(0.25 / 5 / 5), fit.se(), 1e-12);
    }

    @Test
    void fit_withNoMatchedTrial_isNull() {
        assertNull(Estimate.fit(
                Map.of(new Group(20, LONELY), List.of(exposed(12, 0.5, 1))), new Pools(controls()), Map.of()));
    }

    @Test
    void lateHit_countsInUsersHit_notInEstimate() {
        World w = new World().ids(0, 11, 100, 160).ids(12, 12, 100, 120);
        w.hit("C-1", 10, 130).hit("C-1", 11, 130).hit("C-1", 12, 128);
        IssueEstimate est = w.run();
        LineageEstimate e = est.byLineage().get("C-1");
        assertNotNull(e);
        assertAll(
                () -> assertEquals(21, est.h()),
                () -> assertEquals(3, e.usersHit()),
                () -> assertEquals(1, e.lateHits(), "hit day 128 is 8 days after the last series day 120"),
                () -> assertEquals(2, e.nEst()),
                () -> assertEquals(Set.of(10, 11), e.estimateIds()),
                () -> assertEquals(2, e.nM()),
                () -> assertEquals(44, e.trials(), "days 130 to 151 for each compared ID"),
                () -> assertEquals(10, e.controlIds(), "IDs not in the estimate with a trial in the hit week"),
                () -> assertEquals(0, e.unmatched()),
                () -> assertEquals(60, e.draws()),
                () -> assertNull(e.withoutAccounts(), "no clusters"));
    }

    @Test
    void hitSevenDaysAfterTheLastDay_isInTheEstimate() {
        World w = new World().ids(0, 11, 100, 160).ids(12, 12, 100, 123);
        w.hit("C-1", 10, 130).hit("C-1", 11, 130).hit("C-1", 12, 130);
        LineageEstimate e = w.run().byLineage().get("C-1");
        assertNotNull(e);
        assertAll(() -> assertEquals(0, e.lateHits()), () -> assertEquals(Set.of(10, 11, 12), e.estimateIds()));
    }

    @Test
    void usersHit_countsCoveredIds_cannotFollowIdsAreInNeitherSplit() {
        World w = new World().ids(0, 14, 100, 160);
        w.cannotFollow.add(13);
        w.uncovered.add(14);
        w.hit("C-1", 10, 130).hit("C-1", 11, 130).hit("C-1", 13, 130).hit("C-1", 14, 130);
        LineageEstimate e = w.run().byLineage().get("C-1");
        assertNotNull(e);
        assertAll(
                () -> assertEquals(3, e.usersHit()),
                () -> assertEquals(2, e.nEst()),
                () -> assertEquals(0, e.lateHits()),
                () -> assertEquals(Set.of(10, 11), e.estimateIds()));
    }

    @Test
    void idChangeWeeks_areNotCompared() {
        World w = new World().ids(0, 11, 100, 160);
        w.hit("C-1", 10, 130).hit("C-1", 11, 130);
        w.events.add(event(126, false));
        LineageEstimate unsolved = w.run().byLineage().get("C-1");
        w.events.set(0, event(126, true));
        LineageEstimate solved = w.run().byLineage().get("C-1");
        assertNotNull(unsolved);
        assertNotNull(solved);
        assertAll(
                () -> assertEquals(24, unsolved.trials(), "weeks 18 and 19 are dropped: days 140 to 151 are left"),
                () -> assertEquals(
                        0, unsolved.draws(), "the real first hits fall in week 18, so no false hit is drawn"),
                () -> assertEquals(0.0, unsolved.b0()),
                () -> assertEquals(44, solved.trials(), "an event solved by aliases drops nothing"));
    }

    @Test
    void anIssueWithNoComparableId_hasNoEntry() {
        World w = new World().ids(0, 9, 50, 160).ids(10, 10, 100, 110).ids(20, 21, 130, 160);
        w.hit("late", 10, 130).hit("alone", 20, 130).hit("alone", 21, 130);
        IssueEstimate est = w.run();
        assertAll(
                () -> assertFalse(est.byLineage().containsKey("late"), "every hit ID is a late hit"),
                () -> assertFalse(
                        est.byLineage().containsKey("alone"),
                        "new IDs hit on their first day, against IDs 28 days old or more"));
    }

    @Test
    void fewAccounts_rerunsWithoutTheAccountCell() {
        World w = new World().ids(0, 11, 100, 160);
        for (int u = 0; u <= 4; u++) w.account.put(u, 1);
        for (int u = 5; u <= 9; u++) w.account.put(u, 2);
        w.account.put(10, 1);
        w.account.put(11, 1);
        w.hit("C-1", 10, 130).hit("C-1", 11, 130);
        LineageEstimate e = w.run().byLineage().get("C-1");
        assertNotNull(e);
        LineageEstimate without = e.withoutAccounts();
        assertNotNull(without);
        assertAll(
                () -> assertEquals(1.0, e.accEff(), 1e-12),
                () -> assertEquals(2, without.nM()),
                () -> assertEquals(1.0, without.accEff(), 1e-12, "the account sum still reads the real accounts"),
                () -> assertNull(without.withoutAccounts()));
    }

    @Test
    void manyAccounts_doNotRerun() {
        World w = new World().ids(0, 14, 100, 160);
        for (int u = 0; u <= 9; u++) w.account.put(u, 100);
        for (int u = 10; u <= 14; u++) {
            w.account.put(u, u);
            w.hit("C-1", u, 130);
        }
        LineageEstimate e = w.run().byLineage().get("C-1");
        assertNotNull(e);
        assertAll(() -> assertEquals(5.0, e.accEff(), 1e-12), () -> assertNull(e.withoutAccounts()));
    }

    @Test
    void h_readsTheCalendarMedian() {
        World w = new World().ids(0, 11, 100, 160);
        w.mAppCalendar = 6.0;
        w.hit("C-1", 10, 130).hit("C-1", 11, 130);
        IssueEstimate est = w.run();
        LineageEstimate e = est.byLineage().get("C-1");
        assertNotNull(e);
        assertAll(() -> assertEquals(24, est.h()), () -> assertEquals(50, e.trials(), "days 130 to 154"));
    }

    private static IdChangeEvent event(int day, boolean solved) {
        return new IdChangeEvent(new Scope(ScopeKind.APP, null), day, 0, 0, 0, 0, solved, List.of(), List.of());
    }

    private static List<Trial> controls() {
        List<Trial> out = new ArrayList<>();
        for (int u = 1; u <= 5; u++) {
            double y = u == 5 ? 1 : 0;
            out.add(new Trial(u, 140, 1, y, y, CELL, Arm.UNEXPOSED));
        }
        return out;
    }

    private static Trial exposed(int id, double yLo, double yHi) {
        return new Trial(id, 141, 1, yLo, yHi, id == 12 ? LONELY : CELL, Arm.EXPOSED);
    }

    private static LineageEstimate estimate(
            double rateLo, double rateHi, double b0, double se, double s0, int nM, int nEst, double accEff) {
        return new LineageEstimate(
                nEst, nEst, nM, rateLo, rateHi, se, b0, s0, 0, 60, accEff, 0, 0, 0, 0, 0, 0, 0, Set.of(), null);
    }

    /** Daily IDs with one trace a day on route 1, the reach of every issue. */
    private static final class World {
        final int asOf = 200;
        final Map<Integer, List<Integer>> days = new TreeMap<>();
        final Map<Integer, Integer> account = new HashMap<>();
        final Set<Integer> uncovered = new HashSet<>();
        final Set<Integer> cannotFollow = new HashSet<>();
        final List<IdChangeEvent> events = new ArrayList<>();
        final Map<String, Map<Integer, List<Integer>>> hits = new TreeMap<>();

        @Nullable
        Double mAppCalendar;

        World ids(int from, int to, int firstDay, int lastDay) {
            for (int u = from; u <= to; u++) {
                List<Integer> ds = new ArrayList<>();
                for (int d = firstDay; d <= lastDay; d++) ds.add(d);
                days.put(u, ds);
            }
            return this;
        }

        World hit(String lineage, int id, int day) {
            hits.computeIfAbsent(lineage, k -> new TreeMap<>()).put(id, List.of(day));
            return this;
        }

        IssueEstimate run() {
            List<int[]> rows = new ArrayList<>();
            days.forEach((u, ds) -> ds.forEach(d -> rows.add(new int[] {u, d})));
            int n = rows.size();
            int[] id = new int[n];
            int[] day = new int[n];
            int[] route = new int[n];
            int[] traces = new int[n];
            for (int i = 0; i < n; i++) {
                id[i] = rows.get(i)[0];
                day[i] = rows.get(i)[1];
                route[i] = 1;
                traces[i] = 1;
            }
            IdDays idDays = new IdDays(
                    id,
                    day,
                    route,
                    new int[n],
                    traces,
                    new int[n],
                    new int[n],
                    new int[n],
                    new long[n],
                    new int[n],
                    new int[n][0],
                    new long[n][0]);
            Facts facts = new Facts(
                    asOf,
                    0,
                    null,
                    List.of(),
                    idDays,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    new RouteClasses(Map.of(), false),
                    List.of(),
                    List.of(),
                    List.of());
            Map<Integer, IdSeries> series = new TreeMap<>();
            Map<Integer, IdIdentity> identity = new TreeMap<>();
            Map<Integer, IdOdds> odds = new TreeMap<>();
            days.forEach((u, ds) -> {
                series.put(
                        u, new IdSeries(ds.getFirst(), ds, ActivityBasis.HUMAN, ds, List.of(), List.of(), false, null));
                boolean covered = !uncovered.contains(u);
                identity.put(
                        u,
                        new IdIdentity(
                                false,
                                false,
                                false,
                                false,
                                false,
                                "u/10",
                                account.get(u),
                                covered,
                                covered ? null : SetAsideReason.AGGREGATE));
                odds.put(u, new IdOdds(Stratum.E, 0.5, 0.5, 0.5, null, false, false, 0, 0.0));
            });
            GroupStats none = new GroupStats(0, 0, null, null, null, null, false);
            Identity ident = new Identity(identity, Map.of(), Map.of(), none, Map.of(), 1, 0, 0, 1, 1, 0);
            IdChannelResult channel = new IdChannelResult(
                    null,
                    events.isEmpty()
                            ? null
                            : new IdChange(events, events.getFirst().day()),
                    Map.of(),
                    cannotFollow,
                    Set.of());
            Map<String, LineageHits> byLineage = new TreeMap<>();
            hits.forEach((key, hu) -> {
                int first = hu.values().stream().mapToInt(List::getFirst).min().orElseThrow();
                int last = hu.values().stream().mapToInt(List::getLast).max().orElseThrow();
                byLineage.put(key, new LineageHits(hu, first, last, List.of(1), true, false));
            });
            StopOdds stopOdds = new StopOdds(
                    odds, null, mAppCalendar, 14, List.of(), List.of(), null, null, Map.of(), Map.of(), Map.of());
            return Estimate.estimate(facts, new Series(100, series), ident, channel, new HitSet(byLineage), stopOdds);
        }
    }
}
