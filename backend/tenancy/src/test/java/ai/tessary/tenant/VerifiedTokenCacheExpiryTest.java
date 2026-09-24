// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * A rejection is a guess about a row that could be issued or restored, so it must expire. Without an
 * expiry a token rejected once would stay rejected for as long as the entry survived, and a key issued
 * or restored after one failed attempt would never verify.
 */
class VerifiedTokenCacheExpiryTest {

    private static final String BOGUS = ApiKeyService.TOKEN_PREFIX + "w_neverissuedatall";

    @Test
    void aRejectionHoldsForTheNegativeTtlAndIsThenForgotten() {
        TokenCacheProperties props = new TokenCacheProperties();
        props.setNegativeTtlSeconds(10);
        MovableClock clock = new MovableClock(Instant.parse("2026-09-01T00:00:00Z"));
        VerifiedTokenCache cache = new VerifiedTokenCache(props, clock);

        cache.rememberRejected(BOGUS);

        clock.advance(Duration.ofSeconds(10).minusMillis(1));
        assertInstanceOf(
                VerifiedTokenCache.Lookup.Rejected.class,
                cache.lookup(BOGUS),
                "one tick inside the negative TTL the rejection still answers");

        clock.advance(Duration.ofMillis(2));
        assertInstanceOf(
                VerifiedTokenCache.Lookup.Unknown.class,
                cache.lookup(BOGUS),
                "past the negative TTL the rejection is forgotten, not sticky");
    }

    /** A clock that stands still until the test moves it. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("the cache reads millis only");
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
