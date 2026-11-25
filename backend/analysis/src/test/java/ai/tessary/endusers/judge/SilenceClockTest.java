// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.judge;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.tessary.endusers.judge.model.Scope;
import ai.tessary.endusers.judge.model.SilenceResult;
import ai.tessary.endusers.judge.model.SilenceResult.ClockInterval;
import ai.tessary.endusers.judge.model.SilenceResult.SilenceEvent;
import ai.tessary.endusers.model.ScopeKind;
import ai.tessary.endusers.model.SilenceState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The clock weights each day of a held silence by {@code 1 - rho} (spec 4 steps 7 and 8, reading 10 of spec 5). A
 * clock that counts the whole hold, or none of it, makes a gap over a proven break look long in the stop curves; a
 * closed interval moves the end of the hold by one day; an anchor that keeps members of an event covering it fits the
 * curves on a hold the live logic did not apply then.
 */
class SilenceClockTest {

    @Test
    void clock() {
        List<ClockInterval> hold = List.of(new ClockInterval(100, 130, 0.7));
        SilenceResult silence = new SilenceResult(List.of(), Map.of(1, hold));

        assertAll(
                () -> assertEquals(3.0, SilenceClock.length(hold, 110, 120), 1e-12, "10 days at 0.3 each"),
                () -> assertEquals(10.0, SilenceClock.length(List.of(), 110, 120), 1e-12, "no event: the calendar"),
                () -> assertEquals(10.0, SilenceClock.length(hold, 130, 140), 1e-12, "the hold ends before day 130"),
                () -> assertEquals(10.0, SilenceClock.length(hold, 90, 100), 1e-12, "the hold starts on day 100"),
                () -> assertEquals(6.5, SilenceClock.length(hold, 95, 105), 1e-12, "5 calendar days and 5 at 0.3"),
                () -> assertEquals(3.0, SilenceClock.length(silence, 1, 110, 120), 1e-12),
                () -> assertEquals(10.0, SilenceClock.length(silence, 2, 110, 120), 1e-12, "an ID with no hold"));
    }

    @Test
    void coveredAt_namesMembersOfEventsThatCoverTheDay() {
        SilenceResult silence = new SilenceResult(
                List.of(
                        event(10, 20, SilenceState.PROVEN_BREAK, List.of(1)),
                        event(30, 61, SilenceState.OPEN, List.of(2)),
                        event(40, 68, SilenceState.NOT_A_BREAK, List.of(3))),
                Map.of());

        assertAll(
                () -> assertEquals(Set.of(), SilenceClock.coveredAt(silence, 9)),
                () -> assertEquals(Set.of(1), SilenceClock.coveredAt(silence, 10), "from the start day"),
                () -> assertEquals(Set.of(), SilenceClock.coveredAt(silence, 20), "a proven break covers to e_end"),
                () -> assertEquals(Set.of(2, 3), SilenceClock.coveredAt(silence, 60)),
                () -> assertEquals(Set.of(3), SilenceClock.coveredAt(silence, 67), "a closed event covers 28 days"),
                () -> assertEquals(Set.of(), SilenceClock.coveredAt(silence, 68)));
    }

    private static SilenceEvent event(int start, int coverEnd, SilenceState state, List<Integer> members) {
        Integer end = state == SilenceState.PROVEN_BREAK ? coverEnd : null;
        return new SilenceEvent(
                new Scope(ScopeKind.APP, null), start, end, state, false, 100, 0.3, 0.1, 0.7, 0.7, members, coverEnd);
    }
}
