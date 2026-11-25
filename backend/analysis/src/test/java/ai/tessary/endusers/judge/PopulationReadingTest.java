// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.endusers.judge.Cells.Cell;
import ai.tessary.endusers.judge.PopulationReading.Result;
import ai.tessary.endusers.judge.Trials.Arm;
import ai.tessary.endusers.judge.Trials.Trial;
import ai.tessary.endusers.judge.model.HitSet.LineageHits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The second contrast of spec 8.6 (reading 22): all trials of users of the reach after the first evidence against the
 * 28 days before, in cells without week and without "other issue", weight 1. A window that leaks a day, a weighted
 * pool mean, a pool chain that skips a level, a residual sum that merges a cluster with a key index of the same number,
 * or a reading for an always-present finding would let a detector that only marks leavers pass as a cause.
 */
class PopulationReadingTest {

    private static final int FE = 100;
    private static final int LE = 110;
    private static final int H = 14;
    private static final int D = 200;
    private static final Cell CA = new Cell(5, 4, 2, 1, false, null);
    private static final Cell CB = new Cell(5, 4, 2, 2, false, null);
    private static final Cell CC = new Cell(3, 1, 1, 1, false, null);
    private static final Cell CA_EARLIER = new Cell(5, 3, 2, 1, true, null);

    private static LineageHits lineage(boolean alwaysPresent) {
        return new LineageHits(Map.of(11, List.of(FE)), FE, LE, List.of(1), true, alwaysPresent);
    }

    private static Trial t(int id, int day, double weight, double yLo, Cell cell, Arm arm) {
        return new Trial(id, day, weight, yLo, yLo > 0 ? 1 : 0, cell, arm);
    }

    private static List<Trial> before() {
        List<Trial> out = new ArrayList<>();
        out.add(t(1, 80, 3, 0.5, CA, Arm.UNEXPOSED));
        for (int id = 2; id <= 6; id++) out.add(t(id, 80, 1, 0, CA, Arm.UNEXPOSED));
        out.add(t(7, 90, 1, 0, CB, Arm.UNEXPOSED));
        out.add(t(8, 90, 1, 0, CB, Arm.UNEXPOSED));
        out.add(t(9, 72, 1, 0, CC, Arm.UNEXPOSED));
        out.add(t(10, 99, 1, 0, CC, Arm.UNEXPOSED));
        out.add(t(30, 71, 1, 1, CA, Arm.UNEXPOSED));
        return out;
    }

    private static List<Trial> trials() {
        List<Trial> out = before();
        out.add(t(11, 100, 1, 0.9, CA, Arm.EXPOSED));
        out.add(t(12, 105, 1, 0, CA, Arm.UNEXPOSED));
        out.add(t(16, 101, 1, 0.3, CA, Arm.NEITHER));
        out.add(t(7, 102, 1, 0, CA, Arm.UNEXPOSED));
        out.add(t(13, 124, 1, 0.6, CA_EARLIER, Arm.EXPOSED));
        out.add(t(14, 125, 1, 1, CA, Arm.UNEXPOSED));
        out.add(t(15, 110, 1, 1, CC, Arm.UNEXPOSED));
        return out;
    }

    @Test
    void pop_isExtraStopsAfterTheStart_withTheAccountSumInItsSe() {
        Result r = PopulationReading.read(lineage(false), trials(), H, D, Map.of(11, 7, 16, 7));
        assertNotNull(r);
        double h = 0.5 / 6;
        double cluster7 = (0.9 - h) + (0.3 - h);
        double id13 = 0.6 - h;
        double pop = cluster7 - h - h + id13;
        double vg = 16 * (h * (1 - h)) / 6 + 1 * (h * (1 - h)) / 6;
        double se = Math.sqrt(cluster7 * cluster7 + h * h + h * h + id13 * id13 + vg);
        assertAll(
                () -> assertEquals(10, r.before(), "days 72 to 99; day 71 is out"),
                () -> assertEquals(5, r.after(), "day 125 is after last_ev + H; the CC trial has no pool"),
                () -> assertEquals(pop, r.pop(), 1e-12),
                () -> assertEquals(1.8 - 5.0 / 12, r.pop(), 1e-12),
                () -> assertEquals(se, r.se(), 1e-12, "cluster 7 and key index 7 are summed apart"),
                () -> assertEquals(pop + 2 * se, r.popHigh(), 1e-12));
    }

    @Test
    void poolVarianceHasAFloor() {
        List<Trial> rows = new ArrayList<>();
        for (int id = 1; id <= 5; id++) rows.add(t(id, 90, 1, 0, CA, Arm.UNEXPOSED));
        rows.add(t(11, 100, 1, 0, CA, Arm.EXPOSED));
        Result r = PopulationReading.read(lineage(false), rows, H, D, Map.of());
        assertNotNull(r);
        assertEquals(Math.sqrt(0.25 / 5 / 5), r.se(), 1e-12);
    }

    @Test
    void noReading_forAnAlwaysPresentFinding_fewTrialsBefore_orNoneAfter() {
        List<Trial> four = new ArrayList<>();
        for (int id = 1; id <= 4; id++) four.add(t(id, 90, 1, 0, CA, Arm.UNEXPOSED));
        four.add(t(11, 100, 1, 0, CA, Arm.EXPOSED));
        assertAll(
                () -> assertNull(PopulationReading.read(lineage(true), trials(), H, D, Map.of())),
                () -> assertNull(PopulationReading.read(lineage(false), four, H, D, Map.of())),
                () -> assertNull(PopulationReading.read(lineage(false), before(), H, D, Map.of())));
    }

    @Test
    void afterWindow_endsTheDayBeforeD() {
        List<Trial> rows = before();
        rows.add(t(11, 112, 1, 1, CA, Arm.EXPOSED));
        rows.add(t(12, 113, 1, 1, CA, Arm.EXPOSED));
        Result r = PopulationReading.read(lineage(false), rows, H, 113, Map.of());
        assertNotNull(r);
        assertAll(() -> assertEquals(1, r.after()), () -> assertEquals(1 - 0.5 / 6, r.pop(), 1e-12));
    }
}
