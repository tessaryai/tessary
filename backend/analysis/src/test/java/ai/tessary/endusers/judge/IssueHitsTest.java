// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.judge.model.Facts;
import ai.tessary.endusers.judge.model.Facts.HitRow;
import ai.tessary.endusers.judge.model.Facts.IdDays;
import ai.tessary.endusers.judge.model.Facts.IdInfo;
import ai.tessary.endusers.judge.model.Facts.IssueInput;
import ai.tessary.endusers.judge.model.Facts.NullDay;
import ai.tessary.endusers.judge.model.HitSet;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import ai.tessary.endusers.judge.model.RouteClasses;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Hits and reach of spec 8.1 and 8.2. Keeping only the first evidence day, dropping refs with no user ID from the
 * evidence span and the reach, reading an alias member apart from its representative, or a dose that counts routes off
 * the reach would expose and weigh the wrong trials.
 */
class IssueHitsTest {

    private static final int T0 = 20;

    @Test
    void reach() {
        Map<Integer, Double> evidence = new LinkedHashMap<>();
        evidence.put(3, 1.0);
        evidence.put(1, 6.0);
        evidence.put(2, 3.0);
        assertAll(
                () -> assertEquals(List.of(1, 2), IssueHits.reach(evidence), "6 + 3 of 10 covers 90%"),
                () -> assertEquals(List.of(), IssueHits.reach(Map.of())));
    }

    @Test
    void reach_tiesKeepTheOrderTheEvidenceCameIn() {
        Map<Integer, Double> evidence = new LinkedHashMap<>();
        evidence.put(5, 1.0);
        evidence.put(4, 1.0);
        evidence.put(6, 0.1);
        assertEquals(List.of(5, 4), IssueHits.reach(evidence));
    }

    @Test
    void reach_ofAnIssue_readsRefsAndSplitsSessionRefsOverTheirRoutes() {
        Facts facts = facts(
                List.of(issue("a")),
                List.of(hit(0, 1, 40, 1, 6), hit(0, 2, 40, 2, 3), new HitRow(0, 3, 41, null, Map.of(3, 1, 4, 3), 2)),
                List.of(),
                List.of());
        LineageHits a = IssueHits.build(facts, Map.of()).byLineage().get("a");
        assertEquals(List.of(1, 2, 4), a.reachRoutes(), "route 4 gets 1.5 of the session's 2 refs; 10.5 of 11");
    }

    @Test
    void dose_isTheTracesOfTheIdOnTheReachThatDay() {
        Facts facts = facts(
                List.of(issue("a")),
                List.of(),
                List.of(
                        row(1, 40, 1, 2),
                        row(1, 40, 2, 3),
                        row(1, 40, 3, 4),
                        row(1, 41, 3, 1),
                        row(2, 40, 1, 7),
                        row(5, 40, 1, 1)),
                List.of());
        Map<Integer, NavigableMap<Integer, Integer>> dose =
                IssueHits.dose(facts, Map.of(5, 1), List.of(1, 2), Set.of(1));
        assertAll(
                () -> assertEquals(Map.of(40, 6), dose.get(1), "routes 1 and 2, with the alias member's trace"),
                () -> assertFalse(dose.containsKey(2), "an ID outside the set has no dose"),
                () -> assertFalse(dose.containsKey(5), "an alias member is read under its representative"));
    }

    @Test
    void hits_keepEveryEvidenceDay_underTheRepresentative() {
        Facts facts = facts(
                List.of(issue("a")),
                List.of(hit(0, 1, 52, 1, 1), hit(0, 1, 50, 1, 1), hit(0, 7, 52, 1, 1), hit(0, 1, 52, 1, 2)),
                List.of(),
                List.of());
        LineageHits a = IssueHits.build(facts, Map.of(7, 1)).byLineage().get("a");
        assertEquals(Map.of(1, List.of(50, 52)), a.hitDays());
    }

    @Test
    void refsWithNoUserId_countForTheEvidenceSpanAndTheReach() {
        Facts facts = facts(
                List.of(issue("a")),
                List.of(hit(0, null, 45, 9, 5), hit(0, 1, 50, 1, 1), hit(0, null, 70, 9, 5)),
                List.of(),
                List.of());
        LineageHits a = IssueHits.build(facts, Map.of()).byLineage().get("a");
        assertAll(
                () -> assertEquals(45, a.firstEvidenceDay()),
                () -> assertEquals(70, a.lastEvidenceDay()),
                () -> assertEquals(List.of(9), a.reachRoutes()),
                () -> assertEquals(Map.of(1, List.of(50)), a.hitDays()));
    }

    @Test
    void anIssueWithNoRefs_hasNoEvidence() {
        Facts facts = facts(
                List.of(issue("a"), new IssueInput("b", null, List.of("f2"), false, false)),
                List.of(hit(0, 1, 50, 1, 1)),
                List.of(),
                List.of());
        LineageHits b = IssueHits.build(facts, Map.of()).byLineage().get("b");
        assertAll(
                () -> assertTrue(b.hitDays().isEmpty()),
                () -> assertNull(b.firstEvidenceDay()),
                () -> assertNull(b.lastEvidenceDay()),
                () -> assertEquals(List.of(), b.reachRoutes()),
                () -> assertFalse(b.complete(), "the issue's complete flag is kept"),
                () -> assertFalse(b.alwaysPresent()));
    }

    @Test
    void alwaysPresent_whenTheFirstEvidenceIsWithin14DaysOfTheReachTrafficOrOfT0() {
        List<NullDay> traffic = List.of(new NullDay(60, 1, 0, 10, 0), new NullDay(80, 2, 0, 10, 0));
        HitSet hits = IssueHits.build(
                facts(
                        List.of(issue("near"), issue("far"), issue("start"), issue("other")),
                        List.of(hit(0, 1, 73, 1, 1), hit(1, 1, 74, 1, 1), hit(2, 1, 33, 3, 1), hit(3, 1, 93, 2, 1)),
                        List.of(),
                        traffic),
                Map.of());
        assertAll(
                () -> assertTrue(hits.byLineage().get("near").alwaysPresent(), "13 days after route 1 starts"),
                () -> assertFalse(hits.byLineage().get("far").alwaysPresent(), "14 days after route 1 starts"),
                () -> assertTrue(
                        hits.byLineage().get("start").alwaysPresent(), "13 days after t0, on a route with no traffic"),
                () -> assertTrue(hits.byLineage().get("other").alwaysPresent(), "13 days after route 2 starts"),
                () -> assertTrue(hits.byLineage().get("near").complete()));
    }

    private static IssueInput issue(String key) {
        return new IssueInput(key, key, List.of("f-" + key), true, false);
    }

    private static HitRow hit(int issue, @Nullable Integer id, int day, int route, int refs) {
        return new HitRow(issue, id, day, route, Map.of(), refs);
    }

    private static int[] row(int id, int day, int route, int traces) {
        return new int[] {id, day, route, traces};
    }

    private static Facts facts(List<IssueInput> issues, List<HitRow> hits, List<int[]> rows, List<NullDay> nullDays) {
        int n = rows.size();
        int[][] col = new int[4][n];
        for (int i = 0; i < n; i++) for (int c = 0; c < 4; c++) col[c][i] = rows.get(i)[c];
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
        List<IdInfo> ids = List.of();
        return new Facts(
                200,
                T0,
                null,
                ids,
                idDays,
                nullDays,
                List.of(),
                List.of(),
                issues,
                hits,
                new RouteClasses(Map.of(), false),
                List.of(),
                List.of(),
                List.of());
    }
}
