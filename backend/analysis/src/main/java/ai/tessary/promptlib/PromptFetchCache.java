// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.open.obs.Markers;
import ai.tessary.open.obs.StructuredLog;
import ai.tessary.promptlib.PromptService.ResolvedVersion;
import java.time.Clock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The server's fetch cache: found versions by project, name and selector, for {@link PromptCacheProperties#getTtlSeconds}.
 * A 404 is never stored. Each project has a generation; a load reads it before it queries and stores only if it is
 * unchanged, and every prompt write calls {@link #invalidate}, which bumps it and drops the project's entries after
 * the write commits. Under READ COMMITTED a reader on another connection could otherwise store the old row under the
 * new generation, where nothing would drop it. On one node invalidation is exact; another node serves a moved version
 * until the TTL ends. The key scope and capability checks run before the cache, on every request.
 */
@Component
public class PromptFetchCache {

    private static final Logger log = LoggerFactory.getLogger(PromptFetchCache.class);

    private static final long STATS_INTERVAL_MILLIS = 60_000;

    private record Key(String projectId, String name, PromptSelector selector) {}

    private record Cached(ResolvedVersion value, long chars, long expiresAtMillis) {}

    private final PromptCacheProperties props;
    private final Clock clock;

    /** Access order, so iteration starts at the least recently used entry. */
    private final LinkedHashMap<Key, Cached> entries = new LinkedHashMap<>(256, 0.75f, true);

    /** Never evicted, so a project's generation never returns to a value a running load observed. */
    private final Map<String, Long> generations = new HashMap<>();

    private long totalChars;
    private long hits;
    private long misses;
    private long statsLoggedAtMillis;

    @Autowired
    public PromptFetchCache(PromptCacheProperties props) {
        this(props, Clock.systemUTC());
    }

    PromptFetchCache(PromptCacheProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.statsLoggedAtMillis = clock.millis();
    }

    /** {@code load} runs on a miss; what it throws reaches the caller and nothing is stored. */
    public ResolvedVersion fetch(
            String projectId, String name, PromptSelector selector, Supplier<ResolvedVersion> load) {
        if (!props.isEnabled() || props.getTtlSeconds() <= 0) return load.get();
        Key key = new Key(projectId, name, selector);
        long now = clock.millis();
        long observed;
        synchronized (this) {
            logStatsIfDue(now);
            Cached hit = entries.get(key);
            if (hit != null && hit.expiresAtMillis() > now) {
                hits++;
                return hit.value();
            }
            if (hit != null) remove(key, hit);
            misses++;
            observed = generations.getOrDefault(projectId, 0L);
        }
        ResolvedVersion loaded = load.get();
        long chars = (long) loaded.version().bodyJson().length()
                + loaded.version().configJson().length();
        if (chars > props.getMaxBodyChars()) return loaded;
        synchronized (this) {
            if (generations.getOrDefault(projectId, 0L) != observed) return loaded;
            Cached previous = entries.remove(key);
            if (previous != null) totalChars -= previous.chars();
            entries.put(key, new Cached(loaded, chars, now + props.getTtlSeconds() * 1000L));
            totalChars += chars;
            evictOverBounds();
        }
        return loaded;
    }

    /**
     * Call it after the write statement, from every prompt write. Inside a transaction it runs after the commit and
     * not at all on a rollback; outside one it runs now.
     */
    public void invalidate(String projectId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    drop(projectId);
                }
            });
        } else {
            drop(projectId);
        }
    }

    private void drop(String projectId) {
        int dropped = 0;
        synchronized (this) {
            // Bumped inside the monitor and before the sweep, so a racing load is refused whether it would store
            // before or after the removal.
            generations.merge(projectId, 1L, Long::sum);
            Iterator<Map.Entry<Key, Cached>> it = entries.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Key, Cached> e = it.next();
                if (e.getKey().projectId().equals(projectId)) {
                    totalChars -= e.getValue().chars();
                    it.remove();
                    dropped++;
                }
            }
        }
        StructuredLog.debug(log, "prompt.cache.invalidated")
                .message("dropped %d cached prompt fetches after a prompt write", dropped)
                .field("projectId", projectId)
                .field("entriesDropped", dropped)
                .log();
    }

    private void remove(Key key, Cached cached) {
        entries.remove(key);
        totalChars -= cached.chars();
    }

    private void evictOverBounds() {
        Iterator<Map.Entry<Key, Cached>> it = entries.entrySet().iterator();
        while ((entries.size() > props.getMaxEntries() || totalChars > props.getMaxBodyChars()) && it.hasNext()) {
            totalChars -= it.next().getValue().chars();
            it.remove();
        }
    }

    /** Lazy, on a fetch: the backend has no metrics registry, and a quiet minute logs nothing. */
    private void logStatsIfDue(long now) {
        if (now - statsLoggedAtMillis < STATS_INTERVAL_MILLIS) return;
        if (hits + misses > 0) {
            StructuredLog.info(log, Markers.OPS, "prompt.cache.stats")
                    .message("prompt fetch cache: %d hits, %d misses, %d entries", hits, misses, entries.size())
                    .field("hits", hits)
                    .field("misses", misses)
                    .field("entries", entries.size())
                    .field("bodyChars", totalChars)
                    .log();
        }
        hits = 0;
        misses = 0;
        statsLoggedAtMillis = now;
    }
}
