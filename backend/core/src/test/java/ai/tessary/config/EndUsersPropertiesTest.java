// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.testsupport.Props;
import org.junit.jupiter.api.Test;

/**
 * The install keys of the Users view take the documented defaults when no key is set: collection on, every guard on,
 * and the history, window, size and claim limits the judge was built for.
 */
class EndUsersPropertiesTest {

    @Test
    void defaults() {
        EndUsersProperties props = Props.of(EndUsersProperties.class);

        assertTrue(props.collectEnabled(), "collection is on unless the install turns it off");
        assertEquals(400, props.historyDays());
        assertEquals(1_000_000L, props.maxIdDays());
        assertEquals(180, props.judgeWindowDays());
        assertEquals(1800L, props.leaseSeconds());
        assertEquals(5, props.maxAttempts());

        EndUsersProperties.Guards guards = props.guards();
        assertTrue(guards.notJudgedWithoutPastCheck(), "guards.not-judged-without-past-check");
        assertTrue(guards.cannotTellYet(), "guards.cannot-tell-yet");
        assertTrue(guards.historyTooShort(), "guards.history-too-short");
        assertTrue(guards.hitsNotComplete(), "guards.hits-not-complete");
        assertTrue(guards.mixedIdFormats(), "guards.mixed-id-formats");
        assertTrue(guards.findingsFireTogether(), "guards.findings-fire-together");
        assertTrue(guards.outageBanner(), "guards.outage-banner");
    }
}
