// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.config.ClassifierProperties;
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
        ClassifierProperties props = new ClassifierProperties();
        props.setTriageBreakerFailures(1);
        props.setTriageBreakerCooldownSeconds(COOLDOWN);
        TriageLauncherBreaker breaker = new TriageLauncherBreaker(props);
        breaker.recordLauncherFailure("401");
        long before = breaker.secondsRemaining();

        props.setTriageBreakerCooldownSeconds(COOLDOWN * 10);
        breaker.recordLauncherFailure("still 401");

        assertTrue(breaker.secondsRemaining() <= before, "an open trip keeps its first cooldown");
    }

    private static TriageLauncherBreaker breaker() {
        ClassifierProperties props = new ClassifierProperties();
        props.setTriageBreakerFailures(3);
        props.setTriageBreakerCooldownSeconds(COOLDOWN);
        return new TriageLauncherBreaker(props);
    }
}
