// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.detector.groundedness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.classifier.detector.groundedness.GroundednessStatus.State;
import ai.tessary.config.GroundednessProperties.Mode;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** {@link GroundednessStatus#state}: the row's state from the switch, the mode, the model and the sweep job. */
class GroundednessStatusTest {

    private static final Instant NOW = Instant.parse("2026-09-23T14:02:00Z");
    private static final Duration MISSED_RUN = Duration.ofMinutes(120);

    @Test
    void devFollowsTheModelAndTheCursor() {
        assertEquals(State.ON, state(true, Mode.DEV, true, false, null));
        assertEquals(State.ON, state(true, Mode.DEV, true, true, null));
        assertEquals(State.NOT_SCORING, state(true, Mode.DEV, false, true, null));
        assertEquals(State.NOT_SET_UP, state(true, Mode.DEV, false, false, null));
    }

    @Test
    void devIgnoresHowLongAgoTheLastCatchUpWas() {
        assertEquals(State.ON, state(true, Mode.DEV, true, true, NOW.minus(Duration.ofDays(2))));
    }

    @Test
    void productionIsOnWhileACatchUpIsRecentEvenWithTheModelAsleep() {
        assertEquals(State.ON, state(true, Mode.PRODUCTION, false, true, NOW.minus(Duration.ofMinutes(119))));
        assertEquals(State.ON, state(true, Mode.PRODUCTION, false, true, NOW.minus(MISSED_RUN)));
    }

    @Test
    void productionPastTheMissedRunIsNotScoring() {
        assertEquals(State.NOT_SCORING, state(true, Mode.PRODUCTION, false, true, NOW.minus(Duration.ofMinutes(121))));
        assertEquals(State.NOT_SCORING, state(true, Mode.PRODUCTION, true, true, NOW.minus(Duration.ofHours(3))));
        assertEquals(State.NOT_SCORING, state(true, Mode.PRODUCTION, false, true, null));
    }

    @Test
    void productionBeforeTheFirstSweepFollowsTheModel() {
        assertEquals(State.NOT_SET_UP, state(true, Mode.PRODUCTION, false, false, null));
        assertEquals(State.ON, state(true, Mode.PRODUCTION, true, false, null), "set up, first run under way");
    }

    private static State state(
            boolean enabled, Mode mode, boolean available, boolean everSwept, @Nullable Instant caughtUp) {
        return GroundednessStatus.state(enabled, mode, available, everSwept, caughtUp, MISSED_RUN, NOW);
    }
}
