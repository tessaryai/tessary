// SPDX-License-Identifier: Apache-2.0
package ai.tessary.vitals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The flagging rules. These decide what a user is told to care about, so the cases that matter most
 * are the ones where a filter is expected to stay QUIET — a statistical filter earns its place by not
 * firing, and every false alarm here costs attention the drift findings also want.
 */
class VitalsThresholdsTest {

    @Test
    @DisplayName("p95 latency fires only past the ratio, with enough turns")
    void durationRules() {
        assertTrue(VitalsThresholds.durationWorsened(2400L, 2000L, 100, 100), "1.2x exactly");
        assertFalse(VitalsThresholds.durationWorsened(2300L, 2000L, 100, 100), "1.15x is under the bar");
        assertFalse(VitalsThresholds.durationWorsened(9000L, 2000L, 5, 100), "too few turns this window");
        assertFalse(VitalsThresholds.durationWorsened(9000L, 2000L, 100, 5), "no trustworthy baseline");
        assertFalse(VitalsThresholds.durationWorsened(null, 2000L, 100, 100), "no measurement, no verdict");
    }

    @Test
    @DisplayName("cost fires on spend per turn, never on traffic growth")
    void costRules() {
        assertTrue(VitalsThresholds.costWorsened(0.0125, 0.01, 100, 100), "1.25x per-turn cost");
        assertFalse(VitalsThresholds.costWorsened(0.011, 0.01, 100, 100), "1.1x is under the bar");
        // The load-bearing case: 10x the turns at IDENTICAL per-turn cost is growth, not a regression.
        assertFalse(VitalsThresholds.costWorsened(0.01, 0.01, 1000, 100), "traffic growth is not a concern");
        assertFalse(VitalsThresholds.costWorsened(0.05, 0.0, 100, 100), "no baseline spend, no ratio");
    }

    @Test
    @DisplayName("percentiles are nearest-rank and empty-safe")
    void percentiles() {
        assertNull(VitalsThresholds.percentile(new double[0], 0.95), "no data yields no percentile, not zero");
        double[] one = {42};
        assertEquals(42L, VitalsThresholds.percentile(one, 0.95));
        double[] ten = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(5L, VitalsThresholds.percentile(ten, 0.50));
        assertEquals(10L, VitalsThresholds.percentile(ten, 0.95));
        assertEquals(1L, VitalsThresholds.percentile(ten, 0.01), "below the first rank clamps to the first");
    }
}
