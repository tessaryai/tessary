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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves the prompt names of one ingest batch to prompt ids: names cached within {@link #TTL} cost nothing, the rest
 * cost one read for the whole batch. Only found names are cached, so a prompt created after its first linked span is
 * linked from the next batch on. A prompt deleted and created again under its name keeps the old id on new spans for
 * at most the TTL; ingest sits below the prompt feature, so prompt writes cannot evict here.
 */
@Component
public class PromptLinkIds {

    private static final Logger log = LoggerFactory.getLogger(PromptLinkIds.class);

    static final Duration TTL = Duration.ofSeconds(60);

    private static final int MAX_ENTRIES = 10_000;

    /** The read behind the cache; one call per batch at most. */
    @FunctionalInterface
    public interface Lookup {
        Map<String, String> idsByName(String projectId, Set<String> names);
    }

    private record Key(String projectId, String name) {}

    private record Cached(String promptId, Instant expiresAt) {}

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
        this.entries = new LinkedHashMap<>(Math.min(maxEntries, 256), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
                return size() > maxEntries;
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
                    ids.put(name, hit.promptId());
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
        synchronized (entries) {
            found.forEach((name, id) -> entries.put(new Key(projectId, name), new Cached(id, expiresAt)));
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
