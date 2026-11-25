// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.judge.Cells.Cell;
import ai.tessary.endusers.judge.Trials.Arm;
import ai.tessary.endusers.judge.Trials.Trial;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import ai.tessary.endusers.judge.model.IssueEstimate.LineageEstimate;
import ai.tessary.endusers.judge.model.IssueResult.CurvePoint;
import ai.tessary.endusers.judge.model.Series;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.judge.model.StateResult;
import ai.tessary.endusers.judge.model.StateResult.IdState;
import ai.tessary.endusers.judge.model.StopOdds;
import ai.tessary.endusers.judge.model.StopOdds.IdOdds;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.SetAsideReason;
import ai.tessary.endusers.model.Stage;
import ai.tessary.endusers.model.Stratum;
import ai.tessary.endusers.model.UserIdState;
import ai.tessary.endusers.model.Verdict;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The retention curve of spec 8.9 (reading 26) and the drawer's silent hit IDs. Each compared hit ID starts at its hit
 * day, never its join date; it is at risk in step t only when its hit is 7t days or more before {@code D}; a step's
 * event mass is {@code p_stop} on the last series day. Controls come from the same pool rows, until their own hit. An
 * origin at the trial day, a late hit counted as treated, a control kept after its own hit, a curve that rises, or a
 * missing at-risk count would draw a loss the comparison does not hold.
 */
class RetentionCurveTest {

    private static final Cell X = new Cell(5, 4, 2, 0, false, null);

    private final Map<Integer, IdSeries> series = new TreeMap<>();
    private final Map<Integer, IdOdds> odds = new HashMap<>();
    private final Map<Integer, List<Integer>> hitDays = new TreeMap<>();
    private final List<Trial> trials = new ArrayList<>();

    private void id(int id, int firstDay, int lastDay, double pStop) {
        series.put(
                id,
                new IdSeries(
                        firstDay,
                        List.of(firstDay, lastDay),
                        ActivityBasis.HUMAN,
                        List.of(),
                        List.of(),
                        List.of(),
                        false,
                        null));
        odds.put(id, new IdOdds(Stratum.E, pStop, pStop, pStop, null, false, false, 0, 0));
    }

    /** A compared hit ID with its first exposed trial on {@code trialDay}. */
    private void treated(int id, int hitDay, int trialDay, int lastDay, double pStop) {
        id(id, hitDay, lastDay, pStop);
        hitDays.put(id, List.of(hitDay));
        trials.add(new Trial(id, trialDay, 1, 0, 0, X, Arm.EXPOSED));
    }

    private void control(int id, int lastDay, double pStop, @Nullable Integer hitDay) {
        id(id, 57, lastDay, pStop);
        if (hitDay != null) hitDays.put(id, List.of(hitDay));
        trials.add(new Trial(id, 57, 1, 0, 0, X, Arm.UNEXPOSED));
    }

    private void world() {
        for (int id = 1; id <= 8; id++) treated(id, 56, 56, 99, 0.5);
        treated(9, 56, 56, 70, 0.8);
        treated(10, 58, 59, 99, 0.1);
        treated(11, 60, 60, 99, 0.1);
        treated(12, 57, 57, 99, 0.9);
        control(20, 60, 1.0, null);
        control(21, 80, 0.5, null);
        control(22, 99, 0.0, null);
        control(23, 99, 0.2, null);
        control(24, 99, 0.0, 70);
    }

    private List<CurvePoint> curve(int asOf) {
        Set<Integer> compared = Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
        LineageEstimate e =
                new LineageEstimate(12, 11, 11, 0, 0, 0.05, 0, 0.03, 0, 60, 11, 0, 0, 0, 11, 5, 1, 0, compared, null);
        LineageHits hits = new LineageHits(hitDays, 56, 70, List.of(1), true, false);
        StopOdds o = new StopOdds(odds, null, null, 30, List.of(), List.of(), null, null, Map.of(), Map.of(), Map.of());
        return RetentionCurve.curve(trials, e, Set.of(), hits, new Series(0, series), o, asOf);
    }

    @Test
    void curve() {
        world();
        List<CurvePoint> pts = curve(100);
        double h2 = 0.8 / 11;
        double s2 = 1 - h2;
        double v2 = h2 / (11 * (1 - h2));
        double h6 = 0.1 / 9.2;
        double s6 = s2 * (1 - h6);
        double v6 = v2 + h6 / (9.2 * (1 - h6));
        double c4 = 0.8 * (1 - 0.5 / 3);
        double c6 = c4 * (1 - 0.2 / 2.5);
        assertAll(
                () -> assertEquals(6, pts.size(), "the first hit is 44 days before D: 6 whole weeks"),
                () -> assertEquals(
                        List.of(1, 2, 3, 4, 5, 6),
                        pts.stream().map(CurvePoint::week).toList()),
                () -> assertEquals(
                        List.of(11, 11, 10, 10, 10, 9),
                        pts.stream().map(CurvePoint::atRiskHit).toList(),
                        "ID 12 is a late hit; ID 11, hit 40 days before D, leaves at step 6; ID 10 from its hit day"),
                () -> assertEquals(
                        List.of(5, 4, 4, 4, 4, 4),
                        pts.stream().map(CurvePoint::atRiskSimilar).toList()),
                () -> assertEquals(1.0, pts.get(0).hit(), 1e-12),
                () -> assertEquals(s2, pts.get(1).hit(), 1e-12),
                () -> assertEquals(s2, pts.get(4).hit(), 1e-12),
                () -> assertEquals(s6, pts.get(5).hit(), 1e-12),
                () -> assertEquals(s2 - 1.96 * s2 * Math.sqrt(v2), pts.get(1).bandLow(), 1e-12),
                () -> assertEquals(1.0, pts.get(1).bandHigh(), 1e-12),
                () -> assertEquals(s6 - 1.96 * s6 * Math.sqrt(v6), pts.get(5).bandLow(), 1e-12),
                () -> assertEquals(1.0, pts.get(0).bandLow(), 1e-12),
                () -> assertEquals(0.8, pts.get(0).similar(), 1e-12),
                () -> assertEquals(0.8, pts.get(2).similar(), 1e-12, "ID 24 leaves the controls at its own hit"),
                () -> assertEquals(c4, pts.get(3).similar(), 1e-12),
                () -> assertEquals(c6, pts.get(5).similar(), 1e-12));
        for (int i = 1; i < pts.size(); i++) {
            assertTrue(pts.get(i).hit() <= pts.get(i - 1).hit(), "never rises");
            assertTrue(pts.get(i).similar() <= pts.get(i - 1).similar(), "never rises");
        }
    }

    @Test
    void curve_hasAtMostEightWeeklySteps() {
        world();
        List<CurvePoint> pts = curve(200);
        assertEquals(
                List.of(1, 2, 3, 4, 5, 6, 7, 8),
                pts.stream().map(CurvePoint::week).toList());
    }

    @Test
    void controlHazard_isWeightedToTheTreatedMixOfThePools() {
        Cell y = new Cell(4, 2, 1, 0, false, null);
        treated(1, 56, 56, 63, 0);
        for (int id = 2; id <= 4; id++) {
            id(id, 56, 63, 0);
            hitDays.put(id, List.of(56));
            trials.add(new Trial(id, 56, 1, 0, 0, y, Arm.EXPOSED));
        }
        control(20, 58, 1.0, null);
        for (int id = 21; id <= 24; id++) control(id, 63, 0, null);
        for (int id = 30; id <= 34; id++) {
            id(id, 57, 63, 0);
            trials.add(new Trial(id, 57, 1, 0, 0, y, Arm.UNEXPOSED));
        }
        LineageEstimate e = new LineageEstimate(
                4, 4, 4, 0, 0, 0.05, 0, 0.03, 0, 60, 4, 0, 0, 0, 4, 10, 0, 0, Set.of(1, 2, 3, 4), null);
        LineageHits hits = new LineageHits(hitDays, 56, 56, List.of(1), true, false);
        StopOdds o = new StopOdds(odds, null, null, 30, List.of(), List.of(), null, null, Map.of(), Map.of(), Map.of());
        List<CurvePoint> pts = RetentionCurve.curve(trials, e, Set.of(), hits, new Series(0, series), o, 64);
        assertAll(
                () -> assertEquals(1, pts.size()),
                () -> assertEquals(
                        1 - (1 * 0.2 + 3 * 0.0) / 4, pts.get(0).similar(), 1e-12, "one treated ID in X, three in Y"),
                () -> assertEquals(10, pts.get(0).atRiskSimilar()),
                () -> assertEquals(4, pts.get(0).atRiskHit()));
    }

    @Test
    void silentHitIds_onlyForEvidenceOfLossAndFewAccounts() {
        LineageHits hits = new LineageHits(
                Map.of(1, List.of(150), 2, List.of(150), 3, List.of(150), 4, List.of(150)),
                150,
                150,
                List.of(1),
                true,
                false);
        Map<Integer, IdState> byId = new HashMap<>();
        byId.put(1, state(UserIdState.ACTIVE, Stage.SILENT, null));
        byId.put(2, state(UserIdState.STOPPED, Stage.STOPPED, null));
        byId.put(3, state(UserIdState.ACTIVE, Stage.ACTIVE, null));
        byId.put(4, state(UserIdState.UNKNOWN, null, SetAsideReason.AGGREGATE));
        byId.put(5, state(UserIdState.STOPPED, Stage.STOPPED, null));
        StateResult states = new StateResult(byId, null, Map.of(), null, Map.of(), List.of(), List.of(), null, 1);
        assertAll(
                () -> assertEquals(List.of(1, 2), RetentionCurve.silentHitIds(Verdict.EVIDENCE_OF_LOSS, hits, states)),
                () -> assertEquals(List.of(1, 2), RetentionCurve.silentHitIds(Verdict.FEW_ACCOUNTS, hits, states)),
                () -> assertEquals(List.of(), RetentionCurve.silentHitIds(Verdict.NOT_ENOUGH_EVIDENCE, hits, states)),
                () -> assertEquals(List.of(), RetentionCurve.silentHitIds(Verdict.ALWAYS_PRESENT, hits, states)),
                () -> assertEquals(List.of(), RetentionCurve.silentHitIds(Verdict.MOVED_NOT_ADDED, hits, states)),
                () -> assertEquals(List.of(), RetentionCurve.silentHitIds(Verdict.REACHES_NEARLY_ALL, hits, states)));
    }

    private static IdState state(UserIdState s, @Nullable Stage stage, @Nullable SetAsideReason setAside) {
        return new IdState(s, null, null, stage, 3, setAside, false);
    }
}
