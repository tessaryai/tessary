// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.endusers.judge.Isotonic.Block;
import ai.tessary.endusers.judge.Isotonic.Point;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The calibration of the stop odds pools adjacent violators so the calibrated value never falls as the nominal value
 * rises, then reads it by linear interpolation. A wrong merge or read gives silent IDs the wrong stop odds.
 */
class IsotonicTest {

    @Test
    void pav_mergesTheViolatingPair() {
        assertEquals(
                List.of(new Block(1, 0.2), new Block(2.5, 0.45), new Block(4, 0.9)),
                Isotonic.pav(List.of(
                        new Point(1, 0.2, 1), new Point(2, 0.5, 1), new Point(3, 0.4, 1), new Point(4, 0.9, 1))),
                "M5");
    }

    @Test
    void pav_weighsEachPoint() {
        assertEquals(
                List.of(new Block(1.25, 0.49999999999999994)),
                Isotonic.pav(List.of(new Point(1, 0.6, 3), new Point(2, 0.2, 1))));
    }

    @Test
    void pav_mergesBackwardUntilOrdered() {
        assertEquals(
                List.of(new Block(2, 0.39999999999999997)),
                Isotonic.pav(List.of(new Point(1, 0.5, 1), new Point(2, 0.6, 1), new Point(3, 0.1, 1))),
                "the merged pair 0.35 is below 0.5, so it merges again");
    }

    @Test
    void interp_isLinearAndFlatOutside() {
        List<Block> blocks = List.of(new Block(1, 0.2), new Block(3, 0.6));
        assertAll(
                () -> assertEquals(0.4, Isotonic.interp(blocks, 2)),
                () -> assertEquals(0.2, Isotonic.interp(blocks, 0.5)),
                () -> assertEquals(0.6, Isotonic.interp(blocks, 5)));
    }

    @Test
    void interp_withNoBlocksKeepsTheValue() {
        assertEquals(0.37, Isotonic.interp(List.of(), 0.37));
    }
}
