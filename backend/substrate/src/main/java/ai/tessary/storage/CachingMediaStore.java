// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import ai.tessary.open.media.MediaStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * A bounded in-memory read cache in front of {@link PostgresMediaStore}. The {@link Primary}
 * {@link MediaStore} bean, so every caller gets it without a signature change; {@code put} delegates
 * untouched.
 *
 * <p><b>Why.</b> It was built for grading: the judge re-hydrated an {@code image_ref} block to inline
 * bytes inside the per-grader request build, so an image-bearing unit graded by N graders issued N
 * identical reads of the same immutable row. <b>Grading was removed, and that caller with it.</b>
 * The one remaining reader is {@link MediaController}, which serves bytes by id to the trace viewer —
 * so the amplification is now a person or a browser re-opening the same trace, not a fan-out inside one
 * request. That is a weaker case for a cache than the original one. Read this before tuning the budget,
 * and do not read the old rationale back in: if the endpoint's own read pattern does not justify the
 * memory, the honest change is to drop the cache, not to keep it for a caller that no longer exists.
 *
 * <p><b>Why this is safe.</b> {@code media_object} rows are content-addressed by sha256 and
 * insert-only: {@link PostgresMediaStore#put} returns the existing row's id for identical bytes and
 * never updates one, so a given {@code (projectId, id)} maps to the same bytes forever. There is no
 * invalidation to get wrong — the only bound needed is on memory.
 *
 * <p><b>Bounding.</b> Entries are images, so the meaningful budget is bytes, not entry count: an
 * access-ordered {@link LinkedHashMap} evicts least-recently-used until the retained total fits
 * {@code tessary.media.cache.max-bytes}. A single object larger than the whole budget is served but
 * never retained, so one oversized image can't evict everything else. Guarded by a plain monitor:
 * the critical section is a map operation, and the call it protects is a database round trip.
 */
@Component
@Primary
public class CachingMediaStore implements MediaStore {

    /** Default retained-bytes budget. Sized when judge units re-read a handful of images; see the
     * class javadoc — that caller is gone and this number has not been re-derived for the serve endpoint. */
    private static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

    private final MediaStore delegate;
    private final long maxBytes;

    private final Object lock = new Object();
    /** Access-ordered (the {@code true} arg), so iteration yields least-recently-used first. */
    private final Map<String, StoredMedia> cache = new LinkedHashMap<>(16, 0.75f, true);

    private long retainedBytes;

    public CachingMediaStore(
            PostgresMediaStore delegate, @Value("${tessary.media.cache.max-bytes:0}") long configuredMaxBytes) {
        this.delegate = delegate;
        this.maxBytes = configuredMaxBytes > 0 ? configuredMaxBytes : DEFAULT_MAX_BYTES;
    }

    @Override
    public MediaRef put(String projectId, byte[] bytes, String mediaType) {
        // Writes pass straight through. Content-addressing means a put can only ever ADD a mapping,
        // never change one, so there is nothing cached that a write could invalidate.
        return delegate.put(projectId, bytes, mediaType);
    }

    @Override
    public Optional<StoredMedia> get(String projectId, MediaRef ref) {
        String key = projectId + "|" + ref.id();
        synchronized (lock) {
            StoredMedia hit = cache.get(key);
            if (hit != null) return Optional.of(hit);
        }
        // Fetched outside the lock: a miss is a database round trip and must not block other readers.
        // A concurrent duplicate fetch is possible and harmless — both resolve to identical bytes.
        Optional<StoredMedia> loaded = delegate.get(projectId, ref);
        loaded.ifPresent(m -> admit(key, m));
        return loaded;
    }

    /** Insert {@code media} and evict least-recently-used entries until the budget is respected. */
    private void admit(String key, StoredMedia media) {
        long size = media.bytes() == null ? 0 : media.bytes().length;
        if (size > maxBytes) return; // never retain an object bigger than the whole budget
        synchronized (lock) {
            StoredMedia replaced = cache.put(key, media);
            if (replaced != null) retainedBytes -= sizeOf(replaced);
            retainedBytes += size;
            var it = cache.entrySet().iterator();
            while (retainedBytes > maxBytes && it.hasNext()) {
                // Access order ⇒ the iterator yields least-recently-used first.
                Map.Entry<String, StoredMedia> eldest = it.next();
                if (eldest.getKey().equals(key)) continue; // never evict the entry just admitted
                retainedBytes -= sizeOf(eldest.getValue());
                it.remove();
            }
        }
    }

    private static long sizeOf(StoredMedia m) {
        return m.bytes() == null ? 0 : m.bytes().length;
    }

    /** Retained bytes — test seam for the eviction bound. */
    long retainedBytesForTest() {
        synchronized (lock) {
            return retainedBytes;
        }
    }
}
