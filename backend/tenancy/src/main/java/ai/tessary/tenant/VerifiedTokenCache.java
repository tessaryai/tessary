// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import ai.tessary.open.hash.Sha256;
import java.time.Clock;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Skips repeat bcrypt for bearer tokens (a profile put 62% of backend CPU in bcrypt). Verified and rejected tokens live
 * in separate LRU maps, so a flood of invented tokens evicts only other rejections; neither map refuses an entry. Keys
 * are SHA-256 digests, never tokens. Every revocation path must evict, and a {@code @Transactional} one must evict
 * after commit: under READ COMMITTED another connection could otherwise cache the still-live row under the bumped
 * generation. {@code TenantService.deleteProjectAsync} is the worked example. The TTL is only a backstop for
 * out-of-band changes.
 */
@Component
public final class VerifiedTokenCache {

    public sealed interface Lookup {

        record Unknown() implements Lookup {}

        /** @param writeLastUsed whether enough time has passed to touch {@code last_used_at} */
        record Verified(ApiKey key, boolean writeLastUsed) implements Lookup {}

        record Rejected() implements Lookup {}
    }

    private static final Lookup UNKNOWN = new Lookup.Unknown();
    private static final Lookup REJECTED = new Lookup.Rejected();

    private record Entry(ApiKey key, long expiresAtMillis, long lastUsedWrittenAtMillis) {}

    private final TokenCacheProperties props;
    private final Clock clock;

    private final Map<String, Entry> verified;
    private final Map<String, Long> rejected;

    /** Stops an in-flight bcrypt from re-caching a key revoked while it ran. */
    private final AtomicLong generation = new AtomicLong();

    @Autowired
    public VerifiedTokenCache(TokenCacheProperties props) {
        this(props, Clock.systemUTC());
    }

    VerifiedTokenCache(TokenCacheProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.verified = boundedLru(props.getMaxEntries());
        this.rejected = boundedLru(props.getMaxRejections());
    }

    private static <V> Map<String, V> boundedLru(int max) {
        return new LinkedHashMap<>(Math.min(max, 256), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        };
    }

    /** Read it before the database read whose result you intend to cache, never after. */
    public long generation() {
        return generation.get();
    }

    public Lookup lookup(String presented) {
        if (!props.isEnabled()) return UNKNOWN;
        String k = digest(presented);
        long now = clock.millis();
        synchronized (this) {
            Entry hit = verified.get(k);
            if (hit != null) {
                if (hit.expiresAtMillis() > now) {
                    boolean due =
                            now - hit.lastUsedWrittenAtMillis() >= props.getLastUsedWriteIntervalSeconds() * 1000L;
                    if (due) verified.put(k, new Entry(hit.key(), hit.expiresAtMillis(), now));
                    return new Lookup.Verified(hit.key(), due);
                }
                verified.remove(k);
            }
            Long until = rejected.get(k);
            if (until != null) {
                if (until > now) return REJECTED;
                rejected.remove(k);
            }
        }
        return UNKNOWN;
    }

    /**
     * @param observedGeneration from {@link #generation()} before the row was read; a newer invalidation discards the
     * result
     */
    public void rememberVerified(String presented, ApiKey key, long observedGeneration) {
        if (!props.isEnabled()) return;
        long now = clock.millis();
        Entry e = new Entry(key, now + props.getTtlSeconds() * 1000L, now);
        synchronized (this) {
            if (generation.get() != observedGeneration) return;
            verified.put(digest(presented), e);
        }
    }

    public void rememberRejected(String presented) {
        if (!props.isEnabled() || props.getNegativeTtlSeconds() <= 0) return;
        long until = clock.millis() + props.getNegativeTtlSeconds() * 1000L;
        synchronized (this) {
            rejected.put(digest(presented), until);
        }
    }

    public void invalidate(String keyId) {
        evict(e -> e.key().id().equals(keyId));
    }

    /**
     * Project deletion revokes keys in one UPDATE without {@link ApiKeyService}, so without this the project would keep
     * authenticating ingest for a full TTL.
     */
    public void invalidateProject(String projectId) {
        evict(e -> e.key().projectId().equals(projectId));
    }

    private void evict(java.util.function.Predicate<Entry> matches) {
        synchronized (this) {
            // Bumped inside the monitor and before the sweep, so a racing verification is refused whether it would
            // store before or after the removal.
            generation.incrementAndGet();
            Iterator<Map.Entry<String, Entry>> it = verified.entrySet().iterator();
            while (it.hasNext()) {
                if (matches.test(it.next().getValue())) it.remove();
            }
        }
    }

    private static String digest(String presented) {
        return Base64.getEncoder().encodeToString(Sha256.digest(presented));
    }
}
