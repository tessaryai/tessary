// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Covers {@link RelativeTime}: relative tokens resolve to absolute ISO; everything else passes through. */
class RelativeTimeTest {

    private static final Instant NOW = Instant.parse("2026-05-28T12:00:00Z");

    @Test
    void resolvesNowToNow() {
        assertEquals("2026-05-28T12:00:00Z", RelativeTime.resolve("now", NOW));
    }

    @Test
    void resolvesEachUnit() {
        assertEquals("2026-05-28T11:30:00Z", RelativeTime.resolve("now-30m", NOW));
        assertEquals("2026-05-28T06:00:00Z", RelativeTime.resolve("now-6h", NOW));
        assertEquals("2026-05-27T12:00:00Z", RelativeTime.resolve("now-1d", NOW));
        assertEquals("2026-05-21T12:00:00Z", RelativeTime.resolve("now-1w", NOW));
    }

    @Test
    void passesThroughAbsoluteIsoAndNull() {
        assertEquals("2026-05-01T00:00:00Z", RelativeTime.resolve("2026-05-01T00:00:00Z", NOW));
        assertNull(RelativeTime.resolve(null, NOW));
        assertEquals("", RelativeTime.resolve("", NOW));
        // A non-token string is not interpreted.
        assertEquals("yesterday", RelativeTime.resolve("yesterday", NOW));
    }

    @Test
    void resolveTimesRewritesBothBoundsOnly() {
        ImportFilter f = new ImportFilter(null, null, null, null, null, 10, "now-1d", "now", null, "production");
        ImportFilter r = RelativeTime.resolveTimes(f, NOW);
        assertEquals("2026-05-27T12:00:00Z", r.fromTimestamp());
        assertEquals("2026-05-28T12:00:00Z", r.toTimestamp());
        // Untouched fields carry over.
        assertEquals(10, r.limit());
        assertEquals("production", r.environment());
    }

    @Test
    void resolveTimesIsIdentityWhenNothingRelative() {
        ImportFilter f = ImportFilter.empty();
        assertEquals(f, RelativeTime.resolveTimes(f, NOW));
    }
}
