// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LlmPacerTest {

    @Test
    void parseRetryAfter_handlesCompoundDurations() {
        // "Please try again in 1m23.5s"
        assertEquals(83_500L, LlmPacer.parseRetryAfterMs("Please try again in 1m23.5s"));
        assertEquals(5_000L, LlmPacer.parseRetryAfterMs("retry: try again in 5s"));
        assertEquals(250L, LlmPacer.parseRetryAfterMs("try again in 250ms"));
        assertEquals(60_000L, LlmPacer.parseRetryAfterMs("Try Again In 1m"));
    }

    @Test
    void parseRetryAfter_missingPatternReturnsMinusOne() {
        assertEquals(-1L, LlmPacer.parseRetryAfterMs(null));
        assertEquals(-1L, LlmPacer.parseRetryAfterMs(""));
        assertEquals(-1L, LlmPacer.parseRetryAfterMs("some other error"));
    }

    @Test
    void isRateLimit_detectsCommonStatusStrings() {
        assertTrue(LlmPacer.isRateLimit(new RuntimeException("HTTP 429 too many requests")));
        assertTrue(LlmPacer.isRateLimit(new RuntimeException("Rate limit reached for model")));
        assertTrue(LlmPacer.isRateLimit(new RuntimeException("rate_limit_exceeded")));
        assertTrue(LlmPacer.isRateLimit(new RuntimeException(new RuntimeException("429"))));
        assertFalse(LlmPacer.isRateLimit(new RuntimeException("internal server error")));
        assertFalse(LlmPacer.isRateLimit(null));
    }

    @Test
    void backoff_exponentialCappedAtMax() {
        // Static constants: base 2000ms, cap 60_000ms. Doubles per attempt until capped.
        LlmPacer p = new LlmPacer();
        assertEquals(2000L, p.computeBackoffMs(0, new RuntimeException("429")));
        assertEquals(4000L, p.computeBackoffMs(1, new RuntimeException("429")));
        assertEquals(8000L, p.computeBackoffMs(2, new RuntimeException("429")));
        assertEquals(16_000L, p.computeBackoffMs(3, new RuntimeException("429")));
        assertEquals(32_000L, p.computeBackoffMs(4, new RuntimeException("429")));
        assertEquals(60_000L, p.computeBackoffMs(5, new RuntimeException("429"))); // 64000 capped
        assertEquals(60_000L, p.computeBackoffMs(20, new RuntimeException("429"))); // still capped
    }

    @Test
    void backoff_honorsParsedRetryAfter() {
        LlmPacer p = new LlmPacer();
        // Server says retry in 7 seconds; we use that over the exponential default.
        long ms = p.computeBackoffMs(0, new RuntimeException("Rate limit reached. Please try again in 7s."));
        assertEquals(7000L, ms);
    }

    @Test
    void recordCall_updatesLastSeenTokens() {
        LlmPacer p = new LlmPacer();
        assertEquals(1000, p.estimateNextTokens()); // initial seed
        p.recordCall(4250);
        assertEquals(4250, p.estimateNextTokens());
    }
}
