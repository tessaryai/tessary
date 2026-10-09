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
            new HourlyToolTally("2026-10-05T11:00:00Z", "cs-a", 30, 1));

    @Test
    void learningProgress_skipsBucketsFencedOffByReset() {
        // Reset at 10:30 with a fraction: the 10:00 hour began before it, so it is fenced off with the 09:00 one.
        CarriedState reset =
                new CarriedState("cs-a", State.EMPTY, null, null, "epoch", null, null, "2026-10-05T10:30:00.500Z");

        assertEquals(30, CarriedState.learned(reset, TALLIES), "only the hour after the reset");
        assertEquals(180, CarriedState.learned(null, TALLIES), "a key that never swept learns from every hour");
    }
}
