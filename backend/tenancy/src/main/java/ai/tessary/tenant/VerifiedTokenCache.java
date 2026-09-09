// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Remembers, for a short while, which bearer tokens bcrypt has already answered for.
 *
 * <p><b>Why it exists.</b> {@link ApiKeyService#verify} is on every authenticated request, and its cost
 * is dominated by a bcrypt verification at cost 10 — deliberately slow, because that is what bcrypt is
 * for. Re-deriving the same answer for the same token thousands of times a minute buys nothing: a
 * profile of the OTLP ingest path attributed <b>62% of all backend CPU</b> to
 * {@code BCryptOpenBSDProtocol.cryptRaw}, against 1.1% for actually writing the spans. Tokens are
 * machine credentials presented unchanged on every request, so the answer is stable and worth keeping.
 *
 * <h2>What could be done to a cache like this, and what stops it</h2>
 *
 * <p><b>Filling it with rubbish.</b> Anything keyed on a caller-supplied string is a map an anonymous
 * caller can grow. Both maps here are bounded and evict their least recently used entry on insert;
 * neither ever refuses to admit a new entry, because a cache that refuses is a cache an attacker can
 * use to lock out the people it was built for. Overflow costs a bcrypt, which is where we started.
 *
 * <p><b>Flushing the useful half.</b> A single bounded map would be the real hole: a flood of invented
 * tokens would evict every genuine entry and put bcrypt back on the hot path at exactly the moment the
 * instance is under load. So verified tokens and rejected ones live in <em>separate</em> maps with
 * separate caps. Only a caller holding a real token can place an entry in the map that matters; the
 * rejection map is the one anyone can drive, and filling it evicts nothing but other rejections.
 *
 * <p><b>Outliving a revocation.</b> A cache that answers for a revoked key is an authentication bypass,
 * and there are two distinct ways to get one. Both are closed here, and both were found in review rather
 * than by the 590-test suite, which is why they are written down.
 *
 * <p><em>Every</em> revocation path must evict, not just the per-key one. {@link ApiKeyService#revoke}
 * and {@link ApiKeyService#rotate} call {@link #invalidate}; project deletion revokes a whole project's
 * keys in one indexed UPDATE and calls {@link #invalidateProject}. That second path is the one that
 * matters most: {@code ApiKeyRepository.revokeAllForProject} is documented as "the whole access story for
 * a project being deleted", deliberately synchronous so that nothing new lands in a project on its way
 * out — and a cache that kept answering for those keys would reintroduce exactly the window that
 * synchronous revoke exists to close, on the ingest path, which authenticates on the key alone.
 *
 * <p><em>A verification already in flight must not overwrite the eviction.</em> Removing an entry is not
 * enough on its own: a request that read a live row, then spent tens of milliseconds in bcrypt, would
 * store that now-stale row <em>after</em> the revoke had swept a map it was never in. Every invalidation
 * therefore bumps a generation counter, and {@link #rememberVerified} refuses to store a result derived
 * from a read that began before the current generation. The window is small and the consequence was a
 * full TTL of accepting a credential the operator had just withdrawn.
 *
 * <p><b>If you are adding a revocation path, this is the paragraph you need.</b> Evict as you revoke —
 * and if your method is {@code @Transactional}, evict <em>after</em> the transaction completes, never
 * inside it. Evicting inside is not merely early: under READ COMMITTED a verification on another
 * connection reads the bumped generation, then reads your row as still live because you have not
 * committed, and caches it with a generation nothing will invalidate again. That is the same window
 * reopened from the other side, and it is how this class's second bug was written.
 * {@code TenantService.deleteProjectAsync} is the worked example; {@link ApiKeyService#revoke} needs no
 * ceremony only because it is not transactional and its UPDATE autocommits before it evicts.
 *
 * <p>{@link TokenCacheProperties#getTtlSeconds()} is the backstop for changes that reached the database
 * without going through this class at all — a hand-edited row, a restored backup, another replica — and
 * not the primary control. The invalidations scan rather than keeping an id index: the map is bounded at
 * a few thousand entries and revocation is rare, so a scan is cheaper than a second index that could
 * drift out of step with the first and silently keep a revoked key alive.
 *
 * <p><b>Reading the secrets back out.</b> Entries are keyed on the SHA-256 of the presented token, never
 * the token, so a heap dump, a core file or a debugger session yields digests rather than working
 * credentials. The cached {@link ApiKey} carries the row's bcrypt hash exactly as the database already
 * hands it to the caller today; nothing new is retained.
 *
 * <p><b>Timing.</b> A hit answers in microseconds and a miss in tens of milliseconds, so the two are
 * trivially distinguishable. What that reveals is whether a token was recently used — and the only
 * caller who can ask is one already presenting that token. It is not an oracle for anyone else's
 * credential, which is why the key comparison is an ordinary map lookup: {@code MessageDigest.isEqual}
 * would be defending a digest of a value the questioner already holds.
 *
 * <p>Guarded by one monitor rather than a concurrent map. The critical section is a hash lookup and an
 * eviction check, and LRU ordering is the thing being protected; under virtual threads the contention
 * this costs is far below the bcrypt it removes.
 */
@Component
public final class VerifiedTokenCache {

    /** What the cache knows about a presented token. */
    public sealed interface Lookup {

        /** Nothing cached: the caller must do the real verification. */
        record Unknown() implements Lookup {}

        /**
         * This token verified recently.
         *
         * @param key the row it resolved to
         * @param writeLastUsed whether enough time has passed to be worth touching {@code last_used_at}
         */
        record Verified(ApiKey key, boolean writeLastUsed) implements Lookup {}

        /** This exact token was rejected recently; reject it again without paying bcrypt. */
        record Rejected() implements Lookup {}
    }

    private static final Lookup UNKNOWN = new Lookup.Unknown();
    private static final Lookup REJECTED = new Lookup.Rejected();

    private record Entry(ApiKey key, long expiresAtMillis, long lastUsedWrittenAtMillis) {}

    private final TokenCacheProperties props;

    private final Map<String, Entry> verified;
    private final Map<String, Long> rejected;

    /**
     * Bumped by every invalidation. A verification that began before the bump is refused at
     * {@link #rememberVerified}, which is what stops an in-flight bcrypt from re-caching a key that was
     * revoked while it ran.
     */
    private final AtomicLong generation = new AtomicLong();

    public VerifiedTokenCache(TokenCacheProperties props) {
        this.props = props;
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

    /**
     * The generation to quote back to {@link #rememberVerified}. Read it <em>before</em> the database
     * read whose result you intend to cache, never after.
     */
    public long generation() {
        return generation.get();
    }

    /** What is known about {@code presented}, without touching the database. */
    public Lookup lookup(String presented) {
        if (!props.isEnabled()) return UNKNOWN;
        String k = digest(presented);
        long now = System.currentTimeMillis();
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
     * Record that {@code presented} verified against {@code key}, and that its usage was just written.
     *
     * @param observedGeneration the value {@link #generation()} returned before the row was read. If an
     *     invalidation has landed since, the result is discarded rather than cached — it may describe a
     *     key that was revoked while this verification was in flight.
     */
    public void rememberVerified(String presented, ApiKey key, long observedGeneration) {
        if (!props.isEnabled()) return;
        long now = System.currentTimeMillis();
        Entry e = new Entry(key, now + props.getTtlSeconds() * 1000L, now);
        synchronized (this) {
            if (generation.get() != observedGeneration) return;
            verified.put(digest(presented), e);
        }
    }

    /** Record that {@code presented} did not verify, so an immediate repeat need not pay bcrypt again. */
    public void rememberRejected(String presented) {
        if (!props.isEnabled() || props.getNegativeTtlSeconds() <= 0) return;
        long until = System.currentTimeMillis() + props.getNegativeTtlSeconds() * 1000L;
        synchronized (this) {
            rejected.put(digest(presented), until);
        }
    }

    /**
     * Forget everything known about one key id. Called from the same method that writes a revocation or
     * a rotation, so the withdrawal takes effect on the next request rather than after the TTL.
     */
    public void invalidate(String keyId) {
        evict(e -> e.key().id().equals(keyId));
    }

    /**
     * Forget every key of one project. Called from project deletion, which revokes a project's whole key
     * set in one UPDATE without going through {@link ApiKeyService} — so without this the deleted
     * project would keep authenticating ingest for a full TTL.
     */
    public void invalidateProject(String projectId) {
        evict(e -> e.key().projectId().equals(projectId));
    }

    private void evict(java.util.function.Predicate<Entry> matches) {
        synchronized (this) {
            // Bumped inside the monitor and before the sweep, so a verification racing this call is
            // refused at rememberVerified whether it would have stored before or after the removal.
            generation.incrementAndGet();
            Iterator<Map.Entry<String, Entry>> it = verified.entrySet().iterator();
            while (it.hasNext()) {
                if (matches.test(it.next().getValue())) it.remove();
            }
        }
    }

    private static String digest(String presented) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(sha.digest(presented.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
