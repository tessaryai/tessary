// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import ai.tessary.endusers.judge.model.Facts.NullDay;
import ai.tessary.endusers.judge.model.Facts.Version;
import ai.tessary.endusers.judge.model.IdChannelResult;
import ai.tessary.endusers.judge.model.IdChannelResult.IdChange;
import ai.tessary.endusers.judge.model.IdChannelResult.IdChangeEvent;
import ai.tessary.endusers.judge.model.IdChannelResult.IdLoss;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.GroupStats;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.RouteClasses;
import ai.tessary.endusers.judge.model.RouteClasses.Route;
import ai.tessary.endusers.judge.model.RouteClasses.RouteStatistics;
import ai.tessary.endusers.judge.model.Scope;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.RouteClass;
import ai.tessary.endusers.model.ScopeKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The ID channel decides whether a wave of silent IDs is a wave of stops (spec 3.4). An ID loss missed, or found on a
 * one-week blip, moves every stop count after the onset; an ID change found on less than all four conditions, or not
 * solved by the aliases, takes real stops out of the funnel or counts renamed IDs as lost users.
 */
class IdChannelTest {

    private static final int A = 1;
    private static final int OTHER = 2;
    private static final int NULL_VERSION = 1;
    private static final int NAMED_VERSION = 2;

    @Test
    void idLoss_onset() {
        IdChannelResult held = nullShares(55, 10, 10, 10, 10, 190, 190, 190);
        IdChannelResult blip = nullShares(55, 10, 10, 10, 10, 190, 10, 10);
        IdChannelResult small = nullShares(55, 10, 10, 10, 10, 50, 50, 50);
        IdChannelResult latest = nullShares(55, 10, 10, 10, 10, 10, 10, 190);
        IdChannelResult ended = nullShares(55, 10, 10, 10, 10, 190, 190, 10);
        Scenario named = new Scenario(55).version(NAMED_VERSION);
        for (int w = 0; w <= 6; w++) named.week(w, 500, w < 4 ? 10 : 190);

        assertAll(
                () -> assertNotNull(held.idLoss(), "0.02 then 0.38 in two whole weeks"),
                () -> assertEquals(28, held.idLoss().onsetDay(), "onset is the first day of week 4"),
                () -> assertEquals(false, held.idLoss().provisional()),
                () -> assertEquals(List.of("app", "r|1"), held.idLoss().slices(), "a NULL version is no slice"),
                () -> assertNull(blip.idLoss(), "the next whole week must hold too"),
                () -> assertNull(small.idLoss(), "a rise of 0.08 is under 0.10"),
                () -> assertNotNull(latest.idLoss(), "a single latest week has the same effect"),
                () -> assertEquals(42, latest.idLoss().onsetDay()),
                () -> assertEquals(true, latest.idLoss().provisional()),
                () -> assertNull(ended.idLoss(), "the loss is over once the excess is back under 0.05"),
                () -> assertEquals(
                        List.of("app", "rv|1|2", "r|1", "v|2"),
                        named.detect().idLoss().slices(),
                        "a named version is a slice, alone and with its route; labels in string order"));
    }

    @Test
    void idLoss_measuresVanishedIdsAgainstOrphanTraffic() {
        Scenario s = new Scenario(62);
        for (int k = 0; k < 4; k++) s.id(List.of(7 * k, 7 * k + 2, 7 * k + 4), 1);
        List<Integer> lost = new ArrayList<>();
        for (int i = 0; i < 10; i++) lost.add(s.id(List.of(1, 8, 15, 22, 29, 36), 25));
        for (int w = 0; w <= 7; w++) s.week(w, 500, w < 6 ? 10 : 190);

        IdChannelResult result = s.detect();

        IdLoss loss = result.idLoss();
        double kappa = 180.0 / 250.0;
        double vanishedNormal = 1.0 * (62 - 35) / 7.0;
        assertAll(
                () -> assertEquals(42, loss.onsetDay()),
                () -> assertEquals(kappa, loss.kappa(), 1e-12, "180 orphan traces a week against 10 x 100 / 4"),
                () -> assertEquals(
                        vanishedNormal,
                        loss.vanishedNormal(),
                        1e-12,
                        "one ID a week vanishes in normal weeks, over 27 days"),
                () -> assertEquals(kappa * (10 - vanishedNormal) / 10, loss.phi(), 1e-12),
                () -> assertEquals(
                        lost.stream().collect(TreeMap::new, (m, u) -> m.put(u, 1.0), Map::putAll),
                        loss.lostShare(),
                        "every trace of the last 28 days was on the affected app slice"),
                () -> assertEquals(
                        lost.stream().collect(TreeMap::new, (m, u) -> m.put(u, 42), Map::putAll),
                        result.censoredFrom(),
                        "their silence is censored at the onset"));
    }

    @Test
    void idLoss_onOneRoute_readsTheShareOfTrafficOnIt() {
        Scenario s = new Scenario(55);
        List<Integer> lost = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int u = s.idWithoutRows(List.of(1, 8, 15, 22));
            for (int d : List.of(1, 8, 15, 22)) s.row(u, d, A, 3).row(u, d, OTHER, 1);
            lost.add(u);
        }
        s.id(List.of(1, 8, 15, 22), 4);
        for (int w = 0; w <= 6; w++) s.week(w, A, 4500, 90).week(w, OTHER, 500, w < 4 ? 10 : 190);

        IdLoss loss = s.detect().idLoss();

        assertAll(
                () -> assertEquals(List.of("r|2"), loss.slices(), "the app share rises by 0.036 only"),
                () -> assertEquals(28, loss.onsetDay()),
                () -> assertEquals(
                        lost.stream().collect(TreeMap::new, (m, u) -> m.put(u, 0.25), Map::putAll),
                        loss.lostShare(),
                        "one of four traces was on the route; an ID with none there is not in V"),
                () -> assertEquals(1.0, loss.kappa(), "180 orphan traces a week against 16 is capped at 1"),
                () -> assertEquals(1.0, loss.phi()));
    }

    @Test
    void phi() {
        assertAll(
                () -> assertEquals(0.5, IdChannel.phi(0.6, 60, 10), 1e-12),
                () -> assertEquals(0.25, IdChannel.phi(0.3, 60, 10), 1e-12, "no gate at kappa 0.5"),
                () -> assertEquals(0.0, IdChannel.phi(0.0, 60, 10)),
                () -> assertEquals(0.0, IdChannel.phi(0.6, 60, 70), "fewer IDs than vanish in a normal period"),
                () -> assertEquals(0.0, IdChannel.phi(0.6, 0, 10), "no ID in V"));
    }

    @Test
    void idChange_needsAllFourConditions() {
        Change event = change(25, 1, 14, 0);
        IdChannelResult fewVanished = change(15, 1, 14, 0).detect();
        IdChannelResult heavyVanished = change(25, 100, 14, 0).detect();
        IdChannelResult likeNormalNewIds = change(25, 1, 1, 0).detect();

        IdChannelResult result = event.detect();

        IdChange idChange = result.idChange();
        assertAll(
                () -> assertNotNull(idChange, "births excess 40, vanish excess 25, volume conserved"),
                () -> assertEquals(
                        List.of(new IdChangeEvent(
                                new Scope(ScopeKind.APP, null),
                                63,
                                40.0,
                                25.0,
                                102.0,
                                0,
                                false,
                                event.vanished,
                                event.born)),
                        idChange.events(),
                        "week 9 is the first week whose two weeks hold the change in week 10"),
                () -> assertEquals(63, idChange.firstDay()),
                () -> assertEquals(Set.copyOf(event.vanished), result.cannotFollow()),
                () -> assertEquals(Set.copyOf(event.born), result.bornInChange()),
                () -> assertEquals(
                        event.vanished.stream().collect(TreeMap::new, (m, u) -> m.put(u, 63), Map::putAll),
                        result.censoredFrom()),
                () -> assertNull(fewVanished.idChange(), "vanish excess 15 is under half of 40"),
                () -> assertNull(heavyVanished.idChange(), "the new IDs carry under half of the lost traffic"),
                () -> assertNull(likeNormalNewIds.idChange(), "the new IDs are as active as normal new IDs"));
    }

    @Test
    void idChange_solvedByAliases_at50PercentLinks() {
        IdChannelResult half = change(25, 1, 14, 25).detect();
        IdChannelResult underHalf = change(25, 1, 14, 24).detect();

        assertAll(
                () -> assertNull(half.idChange(), "25 of 50 vanished IDs have a link: no further rule applies"),
                () -> assertEquals(Set.of(), half.cannotFollow()),
                () -> assertEquals(Set.of(), half.bornInChange()),
                () -> assertEquals(Map.of(), half.censoredFrom()),
                () -> assertEquals(24, underHalf.idChange().events().getFirst().linked()),
                () -> assertEquals(
                        false, underHalf.idChange().events().getFirst().solvedByAliases(), "24 of 49"),
                () -> assertEquals(25, underHalf.cannotFollow().size()));
    }

    @Test
    void idChange_testsEachShapeKeyAndCluster() {
        Change event = change(25, 1, 14, 0);
        event.scenario.group("u/10");
        for (int u = 50; u < 55; u++) event.scenario.account(u, 7);

        IdChange idChange = event.detect().idChange();

        assertEquals(
                List.of(
                        new IdChangeEvent(
                                new Scope(ScopeKind.APP, null),
                                63,
                                40.0,
                                25.0,
                                102.0,
                                0,
                                false,
                                event.vanished,
                                event.born),
                        new IdChangeEvent(
                                new Scope(ScopeKind.SHAPE, "u/10"),
                                63,
                                40.0,
                                25.0,
                                102.0,
                                0,
                                false,
                                event.vanished,
                                event.born)),
                idChange.events(),
                "the shape key holds every ID; the cluster of five IDs holds no change");
    }

    @Test
    void noTest_withFewerThan3NormalWeeks() {
        IdChannelResult twoWeeks = nullShares(27, 10, 10, 190);
        IdChannelResult threeWeeks = nullShares(34, 10, 10, 10, 190);
        Scenario thinWeek = new Scenario(34);
        thinWeek.week(0, 500, 10).week(1, 150, 3).week(2, 500, 10).week(3, 500, 190);

        assertAll(
                () -> assertNull(new Scenario(5).week(0, 500, 10).detect().idLoss(), "no whole week yet"),
                () -> assertNull(twoWeeks.idLoss(), "two normal weeks before the rise"),
                () -> assertNull(thinWeek.detect().idLoss(), "a week under 200 traces is no normal week"),
                () -> assertEquals(21, threeWeeks.idLoss().onsetDay(), "three normal weeks are enough"));
    }

    /** One week of 500 traces per value, from week 0; each value is the traces with no user ID. */
    private static IdChannelResult nullShares(int asOfDay, int... noUserId) {
        Scenario s = new Scenario(asOfDay);
        for (int w = 0; w < noUserId.length; w++) s.week(w, 500, noUserId[w]);
        return s.detect();
    }

    /**
     * 100 IDs seen once a week from day 1 to the end; {@code vanished} of them stop after week 9. Two new IDs are born
     * every week from week 1, each seen on two days. In week 10, 40 more IDs are born, seen on {@code newIdDays} days
     * with seven traces a week each; {@code linked} earlier IDs end in week 9 with an alias link to one of them.
     */
    private static Change change(int vanished, int vanishedTraces, int newIdDays, int linked) {
        Scenario s = new Scenario(97);
        List<Integer> vanishedIds = new ArrayList<>();
        List<Integer> born = new ArrayList<>();
        List<Integer> weekly =
                IntStream.rangeClosed(0, 13).map(k -> 7 * k + 1).boxed().toList();
        for (int i = 0; i < 100; i++) {
            if (i < vanished) vanishedIds.add(s.id(weekly.subList(0, 10), vanishedTraces));
            else s.id(weekly, 1);
        }
        for (int w = 1; w <= 12; w++) {
            for (int i = 0; i < 2; i++) {
                int u = s.id(List.of(7 * w + 2, 7 * w + 3), 1);
                if (w == 9 || w == 10) born.add(u);
            }
        }
        for (int i = 0; i < 40; i++) {
            int u = newIdDays == 1
                    ? s.id(List.of(70), 7)
                    : s.id(IntStream.range(70, 70 + newIdDays).boxed().toList(), 1);
            born.add(u);
            if (i < linked) s.aliasMember(64, u);
        }
        born.sort(Comparator.naturalOrder());
        return new Change(s, vanishedIds, born);
    }

    private record Change(Scenario scenario, List<Integer> vanished, List<Integer> born) {
        IdChannelResult detect() {
            return scenario.detect();
        }
    }

    /**
     * Covered IDs on two routes of class A, by default with a NULL version, and weekly null-ID counts on the first day
     * of a week.
     */
    private static final class Scenario {

        private static final RouteStatistics STATISTICS =
                new RouteStatistics(0, 0, 0, 0, 0, 0, 0, null, null, null, 0, null);

        private final int asOfDay;
        private final List<IdInfo> ids = new ArrayList<>();
        private final List<int[]> rows = new ArrayList<>();
        private final List<NullDay> nullDays = new ArrayList<>();
        private final Map<Integer, List<Integer>> days = new TreeMap<>();
        private final Map<Integer, Integer> aliasOf = new TreeMap<>();
        private final Map<Integer, Integer> accountOf = new TreeMap<>();
        private int version = NULL_VERSION;
        private String group = "(app)";

        Scenario(int asOfDay) {
            this.asOfDay = asOfDay;
        }

        Scenario version(int versionNo) {
            version = versionNo;
            return this;
        }

        void group(String groupKey) {
            group = groupKey;
        }

        void account(int u, int cluster) {
            accountOf.put(u, cluster);
        }

        int id(List<Integer> seriesDays, int tracesPerDay) {
            int u = idWithoutRows(seriesDays);
            for (int d : seriesDays) row(u, d, A, tracesPerDay);
            return u;
        }

        int idWithoutRows(List<Integer> seriesDays) {
            ids.add(new IdInfo("u", 10, null, seriesDays.getFirst(), seriesDays.getLast(), 1, false));
            int u = ids.size() - 1;
            days.put(u, List.copyOf(seriesDays));
            return u;
        }

        Scenario row(int u, int day, int routeNo, int traces) {
            rows.add(new int[] {u, day, routeNo, version, traces});
            return this;
        }

        void aliasMember(int lastDay, int representative) {
            ids.add(new IdInfo("u", 10, null, 1, lastDay, 1, false));
            aliasOf.put(ids.size() - 1, representative);
        }

        Scenario week(int week, int traces, int noUserId) {
            return week(week, A, traces, noUserId);
        }

        Scenario week(int week, int routeNo, int traces, int noUserId) {
            nullDays.add(new NullDay(7 * week, routeNo, version, traces, noUserId));
            return this;
        }

        IdChannelResult detect() {
            return IdChannel.detect(facts(), series(), identity());
        }

        private Facts facts() {
            List<int[]> sorted = new ArrayList<>(rows);
            sorted.sort(Comparator.<int[]>comparingInt(r -> r[0])
                    .thenComparingInt(r -> r[1])
                    .thenComparingInt(r -> r[2]));
            int n = sorted.size();
            int[][] col = new int[5][n];
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < 5; c++) col[c][i] = sorted.get(i)[c];
            }
            IdDays idDays = new IdDays(
                    col[0],
                    col[1],
                    col[2],
                    col[3],
                    col[4],
                    new int[n],
                    new int[n],
                    new int[n],
                    new long[n],
                    new int[n],
                    new int[n][0],
                    new long[n][0]);
            return new Facts(
                    asOfDay,
                    0,
                    null,
                    List.copyOf(ids),
                    idDays,
                    List.copyOf(nullDays),
                    List.of(),
                    List.of(new Version(NULL_VERSION, null, 0), new Version(NAMED_VERSION, "v1", 0)),
                    List.of(),
                    List.of(),
                    new RouteClasses(
                            Map.of(
                                    A,
                                    new Route(RouteClass.A, null, STATISTICS, false),
                                    OTHER,
                                    new Route(RouteClass.A, null, STATISTICS, false)),
                            false),
                    List.of(),
                    List.of(),
                    List.of());
        }

        private Series series() {
            int t0Id = days.values().stream().mapToInt(List::getFirst).min().orElse(0);
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
                            ds.getFirst() <= t0Id + 1,
                            null)));
            return new Series(t0Id, byId);
        }

        private Identity identity() {
            Map<Integer, IdIdentity> byId = new TreeMap<>();
            for (int u : new TreeSet<>(days.keySet())) {
                byId.put(u, new IdIdentity(false, false, false, false, false, group, accountOf.get(u), true, null));
            }
            return new Identity(
                    byId,
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
    }
}
