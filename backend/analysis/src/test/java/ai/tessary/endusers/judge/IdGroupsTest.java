// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.endusers.judge.DaySeriesTest.FactsBuilder;
import ai.tessary.endusers.judge.IdentityFlags.Flags;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import ai.tessary.endusers.judge.model.Identity;
import ai.tessary.endusers.judge.model.Identity.GroupStats;
import ai.tessary.endusers.judge.model.Identity.IdIdentity;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.SetAsideReason;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * ID groups and coverage decide which IDs the funnel, the past check and every issue count. A group that never returns
 * kept in, a set-aside reason in the wrong order, or a share of traces where the spec asks for a share of ID-days changes
 * the page state and every count on the page.
 */
class IdGroupsTest {

    private static final int D = 200;
    private static final int A = 1;
    private static final int C = 4;
    private static final Flags PLAIN = flags(false, false, false, false, false, 1);

    @Test
    void nonReturningGroup() {
        Identity fiftyOld = identityOfReturners(50, 2, 10, ActivityBasis.MACHINE);
        Identity fortyOld = identityOfReturners(40, 2, 8, ActivityBasis.HUMAN);
        Identity twentyNineOld = identityOfReturners(29, 0, 6, ActivityBasis.HUMAN);

        assertAll(
                () -> assertEquals(
                        new GroupStats(50, 50, 0.04, 0.24, null, 0.76, true),
                        fiftyOld.groups().get("u/10"),
                        "L 0.04 over 30 or more old IDs"),
                () -> assertEquals(
                        SetAsideReason.NON_RETURNING_GROUP,
                        fiftyOld.byId().get(49).setAside(),
                        "a non-returning group comes before machine traffic"),
                () -> assertEquals(false, fortyOld.groups().get("u/10").nonReturning(), "L 0.05"),
                () -> assertEquals(null, fortyOld.byId().get(0).setAside()),
                () -> assertEquals(
                        new GroupStats(29, 29, 0.0, 6 / 29.0, null, 23 / 29.0, false),
                        twentyNineOld.groups().get("(app)"),
                        "29 old IDs are too few to judge"));
    }

    @Test
    void nonReturningGroup_byFewReturnsAndNoCarryOver() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(D));
        Map<Integer, IdSeries> byId = new TreeMap<>();
        for (int i = 0; i < 50; i++) {
            byId.put(f.id(100), human(100, i < 5 ? List.of(100, 110) : List.of(100)));
        }
        Identity noActive = IdGroups.identity(f.build(), Map.of(), new Series(100, byId), plain(byId));
        byId.put(f.id(195), human(195, List.of(195)));
        Identity oneYoungActive = IdGroups.identity(f.build(), Map.of(), new Series(100, byId), plain(byId));

        assertAll(
                () -> assertEquals(
                        new GroupStats(50, 50, 0.1, 0.1, null, 0.9, false),
                        noActive.groups().get("u/10"),
                        "return share 0.1 but no ID was active in the last 14 days"),
                () -> assertEquals(
                        new GroupStats(51, 50, 0.1, 0.1, 0.0, 0.9, true),
                        oneYoungActive.groups().get("u/10"),
                        "return share 0.1 and carry-over 0"));
    }

    @Test
    void covered_excludesEveryReasonInOrder() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(D));
        Map<Integer, IdSeries> byId = new TreeMap<>();
        Map<Integer, Flags> flags = new TreeMap<>();
        int aggregate = add(f, byId, flags, flags(true, false, false, false, true, 1), ActivityBasis.HUMAN);
        int service = add(f, byId, flags, flags(false, false, true, false, false, 1), ActivityBasis.HUMAN);
        int oddShape = add(f, byId, flags, flags(false, false, false, true, true, 1), ActivityBasis.HUMAN);
        int tokenLink = add(f, byId, flags, flags(false, false, false, false, true, 1), ActivityBasis.HUMAN);
        int machine = add(f, byId, flags, PLAIN, ActivityBasis.MACHINE);
        int hinted = add(f, byId, flags, flags(false, true, false, false, false, 1), ActivityBasis.UNATTENDED);

        Identity identity = IdGroups.identity(f.build(), Map.of(), new Series(100, byId), flags);

        assertAll(
                () -> assertEquals(
                        new IdIdentity(true, false, false, false, true, "(app)", null, false, SetAsideReason.AGGREGATE),
                        identity.byId().get(aggregate)),
                () -> assertEquals(
                        new IdIdentity(false, false, true, false, false, "(app)", null, false, SetAsideReason.SERVICE),
                        identity.byId().get(service)),
                () -> assertEquals(
                        new IdIdentity(false, false, false, true, true, "(app)", null, false, SetAsideReason.ODD_SHAPE),
                        identity.byId().get(oddShape)),
                () -> assertEquals(
                        new IdIdentity(
                                false, false, false, false, true, "(app)", null, false, SetAsideReason.TOKEN_LINK),
                        identity.byId().get(tokenLink)),
                () -> assertEquals(
                        new IdIdentity(
                                false, false, false, false, false, "(app)", null, false, SetAsideReason.MACHINE_ONLY),
                        identity.byId().get(machine)),
                () -> assertEquals(
                        new IdIdentity(false, true, false, false, false, "(app)", null, true, null),
                        identity.byId().get(hinted),
                        "a group hint is a warning, not a reason"),
                () -> assertEquals(0.5, identity.linkShare(), "3 of 6 IDs copy a session token"),
                () -> assertEquals(
                        new GroupStats(4, 4, 1.0, 1.0, null, 0.0, false),
                        identity.groups().get("(app)"),
                        "members are the IDs that are neither aggregate nor service"),
                () -> assertEquals(new GroupStats(4, 4, 1.0, 1.0, null, 0.0, false), identity.app()));
    }

    @Test
    void pd_isShareOfIdDays() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(D));
        Map<Integer, IdSeries> byId = new TreeMap<>();
        Map<Integer, Flags> flags = new TreeMap<>();
        add(f, byId, flags, flags(true, false, false, false, false, 3), ActivityBasis.HUMAN);
        add(f, byId, flags, flags(false, false, false, false, false, 6), ActivityBasis.HUMAN);
        add(f, byId, flags, flags(false, true, false, false, false, 3), ActivityBasis.HUMAN);

        Identity identity = IdGroups.identity(f.build(), Map.of(), new Series(100, byId), flags);

        assertAll(
                () -> assertEquals(0.25, identity.pdAggregate()),
                () -> assertEquals(0.75, identity.pdCovered()),
                () -> assertEquals(0.25, identity.pdGroupHint()));
    }

    @Test
    void pd_readsSeriesDaysWhenNoIdHasASizeDay() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(D));
        Map<Integer, IdSeries> byId = new TreeMap<>();
        byId.put(f.id(100), human(100, List.of(100)));
        byId.put(f.id(100), human(100, List.of(100, 101, 102)));
        Map<Integer, Flags> flags =
                Map.of(0, flags(true, false, false, false, false, 0), 1, flags(false, false, false, false, false, 0));

        Identity identity = IdGroups.identity(f.build(), Map.of(), new Series(100, byId), flags);

        assertAll(() -> assertEquals(0.25, identity.pdAggregate()), () -> assertEquals(0.75, identity.pdCovered()));
    }

    @Test
    void identShare_readsRoutesNotOfClassC_andTheLast28Days() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(D))
                .nullDay(D - 28, A, 100, 0)
                .nullDay(D - 27, A, 100, 50)
                .nullDay(D - 10, C, 1000, 1000);
        FactsBuilder empty = DaySeriesTest.standardRoutes(new FactsBuilder(D));
        Series none = new Series(100, Map.of());

        Identity identity = IdGroups.identity(f.build(), Map.of(), none, Map.of());
        Identity noTraces = IdGroups.identity(empty.build(), Map.of(), none, Map.of());

        assertAll(
                () -> assertEquals(0.75, identity.identShare()),
                () -> assertEquals(0.5, identity.identShare28(), "days D - 27 to D"),
                () -> assertEquals(1.0, noTraces.identShare()),
                () -> assertEquals(1.0, noTraces.identShare28()));
    }

    @Test
    void account_needsThreeClustersOfFiveIds() {
        FactsBuilder three = DaySeriesTest.standardRoutes(new FactsBuilder(D));
        Map<Integer, IdSeries> threeIds = new TreeMap<>();
        for (int cluster = 1; cluster <= 4; cluster++) {
            for (int i = 0; i < (cluster == 4 ? 4 : 5); i++) {
                threeIds.put(three.id(new IdInfo("u", 10, cluster, 100, 100, 1, false)), human(100, List.of(100)));
            }
        }
        int noCluster = three.id(new IdInfo("u", 10, null, 100, 100, 1, false));
        threeIds.put(noCluster, human(100, List.of(100)));
        FactsBuilder two = DaySeriesTest.standardRoutes(new FactsBuilder(D));
        Map<Integer, IdSeries> twoIds = new TreeMap<>();
        for (int cluster = 1; cluster <= 2; cluster++) {
            for (int i = 0; i < 5; i++) {
                twoIds.put(two.id(new IdInfo("u", 10, cluster, 100, 100, 1, false)), human(100, List.of(100)));
            }
        }

        Identity withThree = IdGroups.identity(three.build(), Map.of(), new Series(100, threeIds), plain(threeIds));
        Identity withTwo = IdGroups.identity(two.build(), Map.of(), new Series(100, twoIds), plain(twoIds));

        assertAll(
                () -> assertEquals(1, withThree.byId().get(0).account()),
                () -> assertEquals(3, withThree.byId().get(14).account()),
                () -> assertEquals(null, withThree.byId().get(15).account(), "a cluster of 4 IDs"),
                () -> assertEquals(null, withThree.byId().get(noCluster).account()),
                () -> assertEquals(null, withTwo.byId().get(0).account(), "only two clusters of 5"));
    }

    @Test
    void groupKey_fallsBackFromShapeToPrefixToApp() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(D));
        Map<Integer, IdSeries> byId = new TreeMap<>();
        Map<Integer, Flags> flags = new TreeMap<>();
        int u = addShaped(f, byId, flags, "u", 10, 30, 100, PLAIN);
        int young = addShaped(f, byId, flags, "u", 10, 1, 190, PLAIN);
        int v8 = addShaped(f, byId, flags, "v", 8, 15, 100, PLAIN);
        int v9 = addShaped(f, byId, flags, "v", 9, 15, 100, PLAIN);
        int w = addShaped(f, byId, flags, "w", 4, 5, 100, PLAIN);
        int x = addShaped(f, byId, flags, "x", 7, 29, 100, PLAIN);
        int xAggregate = addShaped(f, byId, flags, "x", 7, 1, 100, flags(true, false, false, false, false, 1));
        int yYoung = addShaped(f, byId, flags, "y", 7, 30, 190, PLAIN);

        Identity identity = IdGroups.identity(f.build(), Map.of(), new Series(100, byId), flags);

        assertAll(
                () -> assertEquals("u/10", identity.byId().get(u).groupKey()),
                () -> assertEquals("u/10", identity.byId().get(young).groupKey(), "a young ID joins its shape"),
                () -> assertEquals("v*", identity.byId().get(v8).groupKey()),
                () -> assertEquals("v*", identity.byId().get(v9).groupKey()),
                () -> assertEquals("(app)", identity.byId().get(w).groupKey()),
                () -> assertEquals("(app)", identity.byId().get(x).groupKey(), "an aggregate ID is not counted"),
                () -> assertEquals("(app)", identity.byId().get(xAggregate).groupKey()),
                () -> assertEquals("(app)", identity.byId().get(yYoung).groupKey(), "young IDs are not counted"),
                () -> assertEquals(
                        List.of("(app)", "u/10", "v*"), List.copyOf(new TreeMap<>(identity.groups()).keySet())));
    }

    @Test
    void oneDayShare_perShapeOverOldIdsThatAreNeitherAggregateNorService() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(D));
        Map<Integer, IdSeries> byId = new TreeMap<>();
        Map<Integer, Flags> flags = new TreeMap<>();
        byId.put(f.id(100), human(100, List.of(100)));
        for (int i = 0; i < 3; i++) byId.put(f.id(100), human(100, List.of(100, 101)));
        byId.put(f.id(190), human(190, List.of(190)));
        byId.put(f.id(100), human(100, List.of(100)));
        byId.put(f.id(100), human(100, List.of(100)));
        byId.put(f.id(new IdInfo("v", 8, null, 190, 190, 1, false)), human(190, List.of(190)));
        byId.keySet().forEach(id -> flags.put(id, PLAIN));
        flags.put(5, flags(true, false, false, false, false, 1));
        flags.put(6, flags(false, false, true, false, false, 1));

        Identity identity = IdGroups.identity(f.build(), Map.of(), new Series(100, byId), flags);

        assertEquals(Map.of("u/10", 0.25), identity.oneDayShareByShape());
    }

    @Test
    void identity_keepsTheAliasMap() {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(D));
        f.id(100);
        int rep = f.id(100);
        Map<Integer, IdSeries> byId = Map.of(rep, human(100, List.of(100)));

        Identity identity = IdGroups.identity(f.build(), Map.of(0, rep), new Series(100, byId), plain(byId));

        assertAll(
                () -> assertEquals(Map.of(0, rep), identity.aliasOf()),
                () -> assertEquals(List.of(rep), List.copyOf(identity.byId().keySet())));
    }

    /** {@code old} old IDs: {@code later} last 7 days after their first, {@code soon} two days within 6, the rest one. */
    private static Identity identityOfReturners(int old, int later, int soon, ActivityBasis lastBasis) {
        FactsBuilder f = DaySeriesTest.standardRoutes(new FactsBuilder(D));
        Map<Integer, IdSeries> byId = new TreeMap<>();
        for (int i = 0; i < old; i++) {
            List<Integer> days = i < later ? List.of(100, 107) : i < later + soon ? List.of(100, 103) : List.of(100);
            ActivityBasis basis = i == old - 1 ? lastBasis : ActivityBasis.HUMAN;
            byId.put(f.id(100), new IdSeries(100, days, basis, List.of(), List.of(), List.of(), false, null));
        }
        return IdGroups.identity(f.build(), Map.of(), new Series(100, byId), plain(byId));
    }

    private static int add(
            FactsBuilder f,
            Map<Integer, IdSeries> byId,
            Map<Integer, Flags> flags,
            Flags idFlags,
            ActivityBasis basis) {
        int id = f.id(100);
        byId.put(id, new IdSeries(100, List.of(100, 120), basis, List.of(), List.of(), List.of(), false, null));
        flags.put(id, idFlags);
        return id;
    }

    private static int addShaped(
            FactsBuilder f,
            Map<Integer, IdSeries> byId,
            Map<Integer, Flags> flags,
            String prefix,
            int length,
            int count,
            int firstSeen,
            Flags idFlags) {
        int first = -1;
        for (int i = 0; i < count; i++) {
            int id = f.id(new IdInfo(prefix, length, null, firstSeen, firstSeen, 1, false));
            byId.put(id, human(firstSeen, List.of(firstSeen)));
            flags.put(id, idFlags);
            if (first < 0) first = id;
        }
        return first;
    }

    private static IdSeries human(int firstSeen, List<Integer> days) {
        return new IdSeries(firstSeen, days, ActivityBasis.HUMAN, days, List.of(), List.of(), false, null);
    }

    private static Map<Integer, Flags> plain(Map<Integer, IdSeries> byId) {
        Map<Integer, Flags> flags = new TreeMap<>();
        byId.keySet().forEach(id -> flags.put(id, PLAIN));
        return flags;
    }

    private static Flags flags(
            boolean aggregate, boolean groupHint, boolean service, boolean oddShape, boolean tokenLink, int sizeDays) {
        return new Flags(aggregate, groupHint, service, oddShape, tokenLink, sizeDays);
    }
}
