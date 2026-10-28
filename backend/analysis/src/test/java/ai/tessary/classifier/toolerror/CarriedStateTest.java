// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.toolerror;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.toolerror.ToolErrorDetector.State;
import ai.tessary.classifier.toolerror.ToolErrorRepository.HourlyToolTally;
import java.util.List;
import org.junit.jupiter.api.Test;

/** How far a key with no reference has learned. */
class CarriedStateTest {

    private static final List<HourlyToolTally> TALLIES = List.of(
            new HourlyToolTally("2026-10-05T09:00:00Z", "cs-a", 100, 4),
            new HourlyToolTally("2026-10-05T10:00:00Z", "cs-a", 50, 2),
            new HourlyToolTally("2026-10-05T11:00:00Z", "cs-a", 30, 1),
            // The key's own event clock: its newest hour is never learned.
            new HourlyToolTally("2026-10-05T12:00:00Z", "cs-a", 10, 0));

    @Test
    void learningProgress_skipsBucketsFencedOffByReset() {
        // Reset at 10:30 with a fraction: the 10:00 hour began before it, so it is fenced off with the 09:00 one.
        CarriedState reset =
                new CarriedState("cs-a", State.EMPTY, null, null, "epoch", null, null, "2026-10-05T10:30:00.500Z");

        assertEquals(30, CarriedState.learned(reset, TALLIES, 0), "only the hour after the reset");
        assertEquals(180, CarriedState.learned(null, TALLIES, 0), "a key that never swept learns from every hour");
    }

    /**
     * The Tuning row must count only what the detector has learned: an hour that has not settled, or one at or after
     * an open finding's learning fence, is not in the reference yet.
     */
    @Test
    void learningProgress_countsOnlySettledHoursAndStopsAtTheFence() {
        assertEquals(150, CarriedState.learned(null, TALLIES, 1), "11:00 settles only at 13:00 on the event clock");

        CarriedState fenced = new CarriedState(
                "cs-a", State.EMPTY, null, null, "epoch", null, null, null, null, "2026-10-05T10:00:00Z");
        assertEquals(100, CarriedState.learned(fenced, TALLIES, 0), "only the hour before the fence");
    }

    /**
     * The fence is an onset, not an hour: an onset at 05:37 falls inside the 05:00 bucket, which holds the start of
     * the rise. The bug is that bucket learned into the reference the fence exists to keep it out of.
     */
    @Test
    void theHourHoldingTheFenceIsFenced() {
        CarriedState fenced = new CarriedState(
                "cs-a", State.EMPTY, null, null, "epoch", null, null, null, null, "2026-10-05T05:37:00Z");

        assertEquals(false, fenced.fencedForLearning("2026-10-05T04:00:00Z"), "the hour that ends at the onset");
        assertEquals(true, fenced.fencedForLearning("2026-10-05T05:00:00Z"), "the hour the onset falls in");
        assertEquals(true, fenced.fencedForLearning("2026-10-05T06:00:00Z"));
    }
}
