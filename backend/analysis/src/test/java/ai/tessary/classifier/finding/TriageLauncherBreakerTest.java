// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import ai.tessary.config.ClassifierProperties;
import ai.tessary.testsupport.Props;
import org.junit.jupiter.api.Test;

/**
 * The breaker that parks the triage drain while the launcher refuses everyone. The bugs are tripping on
 * failures that were not consecutive (an intermittent launcher parks a healthy drain), a trip that does not
 * park for the cooldown, a later failure extending an open trip, and a success that leaves it parked.
 */
class TriageLauncherBreakerTest {

    private static final long COOLDOWN = 3600;

    @Test
    void onlyARunOfConsecutiveFailuresTripsItAndOneSuccessClosesIt() {
        TriageLauncherBreaker breaker = breaker();

        breaker.recordLauncherFailure("401");
        breaker.recordLauncherFailure("401");
        breaker.recordReachable();
        breaker.recordLauncherFailure("401");
        breaker.recordLauncherFailure("401");
        assertFalse(breaker.isOpen(), "four failures with a success between them is not a run of three");
        assertEquals(0, breaker.secondsRemaining());

        breaker.recordLauncherFailure("401");
        assertTrue(breaker.isOpen());
        long parked = breaker.secondsRemaining();
        assertTrue(parked > COOLDOWN - 5 && parked <= COOLDOWN, "parked for the cooldown, got " + parked);

        breaker.recordReachable();
        assertFalse(breaker.isOpen(), "a launcher that answered again resumes the drain at once");
        assertEquals(0, breaker.secondsRemaining());
        breaker.recordLauncherFailure("401");
        assertFalse(breaker.isOpen(), "the success reset the run, so one failure does not re-trip");
    }

    /** More failures while parked neither push the cooldown further out nor restart it. */
    @Test
    void failuresWhileOpenDoNotExtendTheTrip() {
        ClassifierProperties props = spy(Props.of(
                ClassifierProperties.class, "triage-breaker-failures", 1, "triage-breaker-cooldown-seconds", COOLDOWN));
        TriageLauncherBreaker breaker = new TriageLauncherBreaker(props);
        breaker.recordLauncherFailure("401");
        long before = breaker.secondsRemaining();

        doReturn(COOLDOWN * 10).when(props).triageBreakerCooldownSeconds();
        breaker.recordLauncherFailure("still 401");

        assertTrue(breaker.secondsRemaining() <= before, "an open trip keeps its first cooldown");
    }

    private static TriageLauncherBreaker breaker() {
        ClassifierProperties props = Props.of(
                ClassifierProperties.class, "triage-breaker-failures", 3, "triage-breaker-cooldown-seconds", COOLDOWN);
        return new TriageLauncherBreaker(props);
    }
}
