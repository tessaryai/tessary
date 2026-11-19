// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import ai.tessary.open.obs.StructuredLog;
import ai.tessary.storage.PromptLinkRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves the prompt names of one ingest batch to prompt ids: names cached within {@link #TTL} cost nothing, the rest
 * cost one read for the whole batch. A name that names no prompt is cached as a miss for the shorter
 * {@link #MISS_TTL}, so a producer that names prompts kept elsewhere costs one read per name in that time, not one per
 * batch, and a prompt created after its first linked span is linked within it. A prompt deleted and created again under
 * its name keeps the old id on new spans for at most the TTL; ingest sits below the prompt feature, so prompt writes
 * cannot evict here.
 */
@Component
public class PromptLinkIds {

    private static final Logger log = LoggerFactory.getLogger(PromptLinkIds.class);

    static final Duration TTL = Duration.ofSeconds(60);

    static final Duration MISS_TTL = Duration.ofSeconds(10);

    private static final int MAX_ENTRIES = 10_000;

    /** The read behind the cache; one call per batch at most. */
    @FunctionalInterface
    public interface Lookup {
        Map<String, String> idsByName(String projectId, Set<String> names);
    }

    private record Key(String projectId, String name) {}

    /** {@code promptId} is null for a miss. */
    private record Cached(@Nullable String promptId, Instant expiresAt) {}

    private final Lookup lookup;
    private final Clock clock;
    private final Map<Key, Cached> entries;

    @Autowired
    public PromptLinkIds(PromptLinkRepository repo) {
        this(repo::idsByName, Clock.systemUTC());
    }

    PromptLinkIds(Lookup lookup, Clock clock) {
        this(lookup, clock, MAX_ENTRIES);
    }

    PromptLinkIds(Lookup lookup, Clock clock, int maxEntries) {
        this.lookup = lookup;
        this.clock = clock;
        this.entries = boundedLru(maxEntries);
    }

    private static Map<Key, Cached> boundedLru(int max) {
        return new LinkedHashMap<>(Math.min(max, 256), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
                return size() > max;
            }
        };
    }

    /** Prompt id by name for the names that name a prompt of the project; the others are absent. */
    public Map<String, String> resolve(String projectId, Set<String> names) {
        Map<String, String> ids = new HashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        Instant now = clock.instant();
        synchronized (entries) {
            for (String name : names) {
                Key key = new Key(projectId, name);
                Cached hit = entries.get(key);
                if (hit != null && hit.expiresAt().isAfter(now)) {
                    String id = hit.promptId();
                    if (id != null) ids.put(name, id);
                } else {
                    if (hit != null) entries.remove(key);
                    missing.add(name);
                }
            }
        }
        if (missing.isEmpty()) return ids;
        Instant started = Instant.now();
        Map<String, String> found = lookup.idsByName(projectId, missing);
        Instant expiresAt = now.plus(TTL);
        Instant missExpiresAt = now.plus(MISS_TTL);
        synchronized (entries) {
            for (String name : missing) {
                String id = found.get(name);
                entries.put(new Key(projectId, name), new Cached(id, id == null ? missExpiresAt : expiresAt));
            }
        }
        ids.putAll(found);
        StructuredLog.debug(log, "ingest.prompt.link.resolved")
                .message("looked up %d prompt name(s), %d named a prompt", missing.size(), found.size())
                .field("projectId", projectId)
                .field("names", missing.size())
                .field("resolved", found.size())
                .durationMs(started)
                .log();
        return ids;
    }
}
