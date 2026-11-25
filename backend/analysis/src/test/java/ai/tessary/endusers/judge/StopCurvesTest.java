// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.endusers.judge.StopCurves.Nominal;
import ai.tessary.endusers.judge.model.Series.IdSeries;
import ai.tessary.endusers.model.ActivityBasis;
import ai.tessary.endusers.model.Stratum;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The nominal stop odds read an ID's silence on the gap curve of IDs like it (spec 5). An ID judged on the wrong
 * stratum, an automation curve used before it has enough gaps and IDs, or a typical gap that ignores the app's own
 * rhythm moves the stop odds of every silent ID.
 */
class StopCurvesTest {

    private static final int D = 1100;
    private static final List<Integer> EVERY_DAY =
            IntStream.range(1000, D).boxed().toList();

    @Test
    void strata() {
        Map<Integer, IdSeries> ids = new TreeMap<>();
        ids.put(0, person(List.of(1000)));
        ids.put(1, person(List.of(1000, 1010, 1020)));
        ids.put(2, person(List.of(1000, 1010, 1020, 1030)));

        Nominal plain = compute(ids);
        Nominal au = compute(withAutomation(30, List.of(1000, 1010, 1020)));
        Nominal fewIds = compute(withAutomation(29, List.of(1000, 1010, 1020)));
        Nominal fewGaps = compute(withAutomation(30, List.of(1000, 1010)));

        assertAll(
                () -> assertEquals(Stratum.K1, plain.byId().get(0).stratum(), "1 series day"),
                () -> assertEquals(Stratum.K2, plain.byId().get(1).stratum(), "3 series days"),
                () -> assertEquals(Stratum.E, plain.byId().get(2).stratum(), "4 series days"),
                () -> assertEquals(Stratum.AU, au.byId().get(0).stratum(), "60 automation gaps of 30 IDs"),
                () -> assertFalse(au.byId().get(0).unattended(), "an ID judged on AU is not unattended"),
                () -> assertEquals(Stratum.K2, fewIds.byId().get(0).stratum(), "58 automation gaps of 29 IDs"),
                () -> assertTrue(fewIds.byId().get(0).unattended(), "silent with automation, no AU curve"),
                () -> assertEquals(Stratum.K2, fewGaps.byId().get(0).stratum(), "30 automation gaps of 30 IDs"),
                () -> assertTrue(fewGaps.byId().get(0).unattended()));
    }

    @Test
    void mU() {
        assertAll(
                () -> assertEquals(3.6, StopCurves.mU(new double[] {2, 4, 6}, 3), 1e-12, "(12 + 2 * 3) / (3 + 2)"),
                () -> assertEquals(0.5, StopCurves.mU(new double[] {0.1, 0.1, 0.1}, 0.1), "0.1 is under the floor"));
    }

    private static Nominal compute(Map<Integer, IdSeries> ids) {
        Map<Integer, List<Integer>> days = new TreeMap<>();
        ids.forEach((u, s) -> days.put(u, s.days()));
        return StopCurves.compute(D, days, ids.keySet(), Map.of(), Map.of(), ids, Set.of());
    }

    private static Map<Integer, IdSeries> withAutomation(int count, List<Integer> days) {
        Map<Integer, IdSeries> ids = new TreeMap<>();
        for (int u = 0; u < count; u++) {
            ids.put(u, new IdSeries(1000, days, ActivityBasis.HUMAN, days, EVERY_DAY, List.of(), false, 1.0));
        }
        return ids;
    }

    private static IdSeries person(List<Integer> days) {
        return new IdSeries(days.getFirst(), days, ActivityBasis.HUMAN, days, List.of(), List.of(), false, null);
    }
}
