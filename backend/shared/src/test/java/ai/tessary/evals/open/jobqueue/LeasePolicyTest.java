// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.jobqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LeasePolicyTest {

    private static final LeasePolicy POLICY = new LeasePolicy(10, Duration.ofSeconds(30), 3);

    @Test
    void rejectsNonPositiveBatch() {
        assertThrows(IllegalArgumentException.class, () -> new LeasePolicy(0, Duration.ofSeconds(30), 3));
    }

    @Test
    void rejectsNonPositiveLeaseDuration() {
        assertThrows(IllegalArgumentException.class, () -> new LeasePolicy(10, Duration.ZERO, 3));
        assertThrows(IllegalArgumentException.class, () -> new LeasePolicy(10, Duration.ofSeconds(-1), 3));
    }

    @Test
    void rejectsNonPositiveMaxAttempts() {
        assertThrows(IllegalArgumentException.class, () -> new LeasePolicy(10, Duration.ofSeconds(30), 0));
    }

    @Test
    void expiresAtAddsLeaseDuration() {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        assertEquals(Instant.parse("2026-07-01T00:00:30Z"), POLICY.expiresAt(now));
    }

    @Test
    void isExhaustedOnlyAtOrOverTheCap() {
        assertFalse(POLICY.isExhausted(2));
        assertTrue(POLICY.isExhausted(3));
        assertTrue(POLICY.isExhausted(4));
    }
}
