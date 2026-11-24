// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.endusers.judge.Stats.CircPairs;
import org.junit.jupiter.api.Test;

/**
 * The Wilson limits gate the tracer verdict and the at-risk cells, the quantile sets route pace cuts and every median,
 * and the circular pair count is the schedule test of a route. A drift in any of them moves a verdict across its
 * threshold.
 */
class StatsTest {

    @Test
    void wilsonLow_matchesHandValues() {
        assertAll(
                () -> assertEquals(0.882, Stats.wilsonLow(34, 35, 1.64), 0.0005, "M1"),
                () -> assertEquals(0.062, Stats.wilsonLow(3, 20, 1.64), 0.0005, "M2"),
                () -> assertEquals(
                        0.06173880085113999,
                        Stats.wilsonLow(3, 20, 1.64),
                        "the reference operation order, so a value at a cut lands on the same side"));
    }

    @Test
    void wilsonUp_matchesReference() {
        assertAll(
                () -> assertEquals(0.3212384221937793, Stats.wilsonUp(3, 20, 1.64)),
                () -> assertEquals(0.9935758087815628, Stats.wilsonUp(34, 35, 1.64)));
    }

    @Test
    void wilson_withNoTrialsIsTheWholeRange() {
        assertAll(
                () -> assertEquals(0.0, Stats.wilsonLow(0, 0, 1.64)),
                () -> assertEquals(1.0, Stats.wilsonUp(0, 0, 1.64)));
    }

    @Test
    void quantile_interpolatesBetweenRanks() {
        assertAll(
                () -> assertEquals(2.5, Stats.quantile(new double[] {1, 2, 3, 4}, 0.5), "M7"),
                () -> assertEquals(2.5, Stats.quantile(new double[] {4, 1, 3, 2}, 0.5), "the input is sorted first"),
                () -> assertEquals(4.0, Stats.quantile(new double[] {1, 2, 3, 4}, 1.0), "the top rank has no next"),
                () -> assertEquals(7.0, Stats.quantile(new double[] {7}, 0.9), "one value is every quantile"),
                () -> assertEquals(
                        2.9000000000000004,
                        Stats.quantile(
                                new double[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20},
                                0.1)),
                () -> assertEquals(3.0, Stats.median(new double[] {5, 1, 3, 2, 4})));
    }

    @Test
    void quantile_leavesTheInputInPlace() {
        double[] xs = {4, 1, 3, 2};
        Stats.quantile(xs, 0.5);
        assertEquals(4.0, xs[0], "callers reuse their arrays");
    }

    @Test
    void quantile_ofNothingIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Stats.quantile(new double[0], 0.5));
    }

    @Test
    void circPairs_countsUnorderedPairsWithinBothWindows() {
        assertAll(
                () -> assertEquals(
                        new CircPairs(15, 15), Stats.circPairs(new int[] {540, 540, 540, 540, 540, 540}), "M8 a"),
                () -> assertEquals(
                        new CircPairs(10, 1),
                        Stats.circPairs(new int[] {540, 541, 560, 580, 600}),
                        "M8 b: 60 minutes apart is within the window"),
                () -> assertEquals(new CircPairs(0, 0), Stats.circPairs(new int[] {7})));
    }

    @Test
    void circPairs_wrapsAroundMidnight() {
        assertAll(
                () -> assertEquals(new CircPairs(1, 0), Stats.circPairs(new int[] {5, 1435}), "M8 c: 10 minutes apart"),
                () -> assertEquals(new CircPairs(1, 1), Stats.circPairs(new int[] {1439, 1})));
    }
}
