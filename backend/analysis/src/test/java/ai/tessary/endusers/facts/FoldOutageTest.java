// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The tracing-outage flag: a day on which every route fell under 30% of its median over the 28 days before. A missed
 * flag lets a tracing outage read as users who stopped; a false one puts a banner on a normal day.
 */
class FoldOutageTest {

    @Test
    void outageFlag() {
        Map<Integer, int[]> history = Map.of(1, days(100), 2, days(100));

        assertAll(
                () -> assertTrue(FoldOutage.outage(Map.of(1, 25, 2, 25), history), "every route at 25%"),
                () -> assertFalse(FoldOutage.outage(Map.of(1, 25, 2, 35), history), "one route at 35%"),
                () -> assertTrue(FoldOutage.outage(Map.of(), history), "a day with no traces at all"));
    }

    @Test
    void outage_medianOfEvenCount_isTheMeanOfTheMiddleTwo_andTheCutIsStrict() {
        int[] half = new int[28];
        Arrays.fill(half, 0, 14, 100);
        Map<Integer, int[]> history = Map.of(1, half);

        assertAll(
                () -> assertTrue(FoldOutage.outage(Map.of(1, 14), history), "median 50, 14 is under 15"),
                () -> assertFalse(FoldOutage.outage(Map.of(1, 15), history), "15 is not under 15"));
    }

    @Test
    void outage_needsARouteWithAMedian_andANewRouteWithTrafficIsNotDown() {
        int[] rare = new int[28];
        Arrays.fill(rare, 0, 13, 100);

        assertAll(
                () -> assertFalse(FoldOutage.outage(Map.of(1, 0), Map.of(1, rare)), "median 0 on short history"),
                () -> assertFalse(FoldOutage.outage(Map.of(), Map.of()), "no history at all"),
                () -> assertFalse(
                        FoldOutage.outage(Map.of(1, 10, 2, 40), Map.of(1, days(100))),
                        "route 2 has no median and still carries traffic"));
    }

    private static int[] days(int traces) {
        int[] counts = new int[28];
        Arrays.fill(counts, traces);
        return counts;
    }
}
