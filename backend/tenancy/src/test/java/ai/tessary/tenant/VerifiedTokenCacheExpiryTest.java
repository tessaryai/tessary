// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    /**
     * The bugs: a verified token outlives the configured TTL, so a row revoked behind the cache's back (a
     * hand edit, a restore) keeps authenticating; or {@code last_used_at} is written on every hit, or never
     * again, instead of once per configured interval.
     */
    @Test
    void aVerificationHoldsForTheTtlAndWritesLastUsedOncePerInterval() {
        TokenCacheProperties props = new TokenCacheProperties();
        props.setTtlSeconds(90);
        props.setLastUsedWriteIntervalSeconds(30);
        MovableClock clock = new MovableClock(Instant.parse("2026-09-01T00:00:00Z"));
        VerifiedTokenCache cache = new VerifiedTokenCache(props, clock);
        ApiKey key = key("key-1");

        cache.rememberVerified(TOKEN_A, key, cache.generation());

        clock.advance(Duration.ofSeconds(30).minusMillis(1));
        assertEquals(new VerifiedTokenCache.Lookup.Verified(key, false), cache.lookup(TOKEN_A), "usage just written");
        clock.advance(Duration.ofMillis(1));
        assertEquals(new VerifiedTokenCache.Lookup.Verified(key, true), cache.lookup(TOKEN_A), "interval elapsed");
        clock.advance(Duration.ofMillis(1));
        assertEquals(
                new VerifiedTokenCache.Lookup.Verified(key, false),
                cache.lookup(TOKEN_A),
                "the due write restarts the interval");

        clock.advance(Duration.ofSeconds(60).minusMillis(2));
        assertEquals(
                new VerifiedTokenCache.Lookup.Verified(key, true), cache.lookup(TOKEN_A), "one tick inside the TTL");
        clock.advance(Duration.ofMillis(1));
        assertInstanceOf(
                VerifiedTokenCache.Lookup.Unknown.class, cache.lookup(TOKEN_A), "at the TTL the entry is forgotten");
    }

    /**
     * The bugs: the configured caps are ignored, so a flood of tokens grows either map without bound; or the
     * verified map evicts the entry just used rather than the least recently used one.
     */
    @Test
    void eachMapEvictsItsLeastRecentlyUsedEntryPastItsCap() {
        TokenCacheProperties props = new TokenCacheProperties();
        props.setMaxEntries(2);
        props.setMaxRejections(1);
        VerifiedTokenCache cache =
                new VerifiedTokenCache(props, new MovableClock(Instant.parse("2026-09-01T00:00:00Z")));

        cache.rememberVerified(TOKEN_A, key("key-a"), cache.generation());
        cache.rememberVerified(TOKEN_B, key("key-b"), cache.generation());
        cache.lookup(TOKEN_A);
        cache.rememberVerified(TOKEN_C, key("key-c"), cache.generation());
        assertInstanceOf(VerifiedTokenCache.Lookup.Verified.class, cache.lookup(TOKEN_A), "recently used, kept");
        assertInstanceOf(VerifiedTokenCache.Lookup.Unknown.class, cache.lookup(TOKEN_B), "least recently used");
        assertInstanceOf(VerifiedTokenCache.Lookup.Verified.class, cache.lookup(TOKEN_C), "newest, kept");

        cache.rememberRejected(BOGUS);
        cache.rememberRejected(BOGUS + "2");
        assertInstanceOf(VerifiedTokenCache.Lookup.Unknown.class, cache.lookup(BOGUS), "evicted past one rejection");
        assertInstanceOf(VerifiedTokenCache.Lookup.Rejected.class, cache.lookup(BOGUS + "2"));
    }

    static Stream<Arguments> nonPositiveCaps() {
        return Stream.of(
                Arguments.of("max-entries", (Consumer<TokenCacheProperties>) p -> p.setMaxEntries(0)),
                Arguments.of("max-rejections", (Consumer<TokenCacheProperties>) p -> p.setMaxRejections(0)));
    }

    /**
     * The bug: a zero cap is accepted, and the cache it builds evicts every entry the moment it is stored, so
     * every request pays bcrypt again while the operator believes caching is on.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nonPositiveCaps")
    void aCapBelowOneIsRefusedAtBinding(String cap, Consumer<TokenCacheProperties> set) {
        TokenCacheProperties props = new TokenCacheProperties();
        assertThrows(IllegalArgumentException.class, () -> set.accept(props), cap);
    }

    private static final String TOKEN_A = ApiKeyService.TOKEN_PREFIX + "w_tokenaaaaaaaaaa";
    private static final String TOKEN_B = ApiKeyService.TOKEN_PREFIX + "w_tokenbbbbbbbbbb";
    private static final String TOKEN_C = ApiKeyService.TOKEN_PREFIX + "w_tokencccccccccc";

    private static ApiKey key(String id) {
        return ApiKey.of(
                id, "proj-1", "user-1", "ci", "tsy_w_prefix", "hash", "2026-09-01T00:00:00Z", null, null, "write");
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
