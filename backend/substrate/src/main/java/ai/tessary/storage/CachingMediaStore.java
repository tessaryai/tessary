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
 * A bounded read cache in front of {@link PostgresMediaStore}. Built for grading, which is gone; the only reader now is
 * {@link MediaController}, so drop the cache rather than tune it if that read pattern does not justify the memory. Safe
 * because media rows are content-addressed and insert-only. Bounded by bytes via LRU; an object larger than the budget
 * is served but never retained.
 */
@Component
@Primary
public class CachingMediaStore implements MediaStore {

    /** Not re-derived since the grading caller went away; see the class javadoc. */
    private static final long DEFAULT_MAX_BYTES = 64L * 1024 * 1024;

    private final MediaStore delegate;
    private final long maxBytes;

    private final Object lock = new Object();
    /** Access-ordered, so iteration yields least-recently-used first. */
    private final Map<String, StoredMedia> cache = new LinkedHashMap<>(16, 0.75f, true);

    private long retainedBytes;

    public CachingMediaStore(
            PostgresMediaStore delegate, @Value("${tessary.media.cache.max-bytes:0}") long configuredMaxBytes) {
        this.delegate = delegate;
        this.maxBytes = configuredMaxBytes > 0 ? configuredMaxBytes : DEFAULT_MAX_BYTES;
    }

    @Override
    public MediaRef put(String projectId, byte[] bytes, String mediaType) {
        // Content-addressing means a put only ever adds a mapping, so nothing cached can be invalidated.
        return delegate.put(projectId, bytes, mediaType);
    }

    @Override
    public Optional<StoredMedia> get(String projectId, MediaRef ref) {
        String key = projectId + "|" + ref.id();
        synchronized (lock) {
            StoredMedia hit = cache.get(key);
            if (hit != null) return Optional.of(hit);
        }
        // Outside the lock so a miss does not block other readers; a duplicate fetch is harmless.
        Optional<StoredMedia> loaded = delegate.get(projectId, ref);
        loaded.ifPresent(m -> admit(key, m));
        return loaded;
    }

    private void admit(String key, StoredMedia media) {
        long size = media.bytes().length;
        if (size > maxBytes) return; // never retain an object bigger than the whole budget
        synchronized (lock) {
            StoredMedia replaced = cache.put(key, media);
            if (replaced != null) retainedBytes -= sizeOf(replaced);
            retainedBytes += size;
            var it = cache.entrySet().iterator();
            // The entry just admitted is last and fits, so the loop stops before reaching it.
            while (retainedBytes > maxBytes) {
                Map.Entry<String, StoredMedia> eldest = it.next();
                retainedBytes -= sizeOf(eldest.getValue());
                it.remove();
            }
        }
    }

    private static long sizeOf(StoredMedia m) {
        return m.bytes().length;
    }
}
