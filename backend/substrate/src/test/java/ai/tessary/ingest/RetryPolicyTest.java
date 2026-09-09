// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void cappedExponentialWithoutHint() {
        RetryPolicy p = RetryPolicy.DEFAULT;
        assertEquals(2000, p.backoffMs(1, -1));
        assertEquals(4000, p.backoffMs(2, -1));
        assertEquals(8000, p.backoffMs(3, -1));
        assertEquals(16000, p.backoffMs(4, -1));
        assertEquals(60_000, p.backoffMs(20, -1), "exponential is capped, and a large attempt can't overflow");
    }

    @Test
    void retryAfterHintWinsOverExponentialButIsCapped() {
        RetryPolicy p = RetryPolicy.DEFAULT;
        assertEquals(5000, p.backoffMs(1, 5000), "hint wins on the first retry");
        assertEquals(5000, p.backoffMs(4, 5000), "hint wins regardless of attempt");
        assertEquals(60_000, p.backoffMs(1, 999_999), "an oversized hint is capped at maxBackoffMs");
        assertEquals(2000, p.backoffMs(1, -1), "no hint falls back to exponential");
        assertEquals(2000, p.backoffMs(1, 0), "a non-positive hint is ignored");
    }

    @Test
    void interactiveIsFailFast() {
        assertEquals(2, RetryPolicy.INTERACTIVE.maxAttempts(), "one quick retry at most");
        assertEquals(
                2000,
                RetryPolicy.INTERACTIVE.backoffMs(1, 60_000),
                "a long Retry-After is capped to the short interactive ceiling so the UI never hangs");
        assertEquals(500, RetryPolicy.INTERACTIVE.backoffMs(1, -1));
    }

    @Test
    void rejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 1, 1), "maxAttempts must be >= 1");
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, -1, 1), "back-off must be >= 0");
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(1, 1, -1), "back-off must be >= 0");
    }
}
