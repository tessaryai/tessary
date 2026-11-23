// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Bounds for {@link PromptFetchCache}. */
@Component
@ConfigurationProperties(prefix = "tessary.prompts.fetch-cache")
public class PromptCacheProperties {

    /** Kill switch: false sends every fetch to the database. */
    private boolean enabled = true;

    /**
     * Not the invalidation window on one node: every prompt write drops the project's entries after commit. It bounds
     * how long another node serves a version that moved, since nothing invalidates across nodes. Zero turns the cache
     * off.
     */
    private long ttlSeconds = 60;

    /** Past it the least recently used entry is evicted. */
    private int maxEntries = 10_000;

    /** The stored body and config characters of every entry together; past it the least recently used is evicted. */
    private long maxBodyChars = 64_000_000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long v) {
        if (v < 0) {
            throw new IllegalArgumentException(
                    "tessary.prompts.fetch-cache.ttl-seconds must not be negative, not " + v);
        }
        this.ttlSeconds = v;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int v) {
        if (v < 1) {
            throw new IllegalArgumentException("tessary.prompts.fetch-cache.max-entries must be at least 1, not " + v);
        }
        this.maxEntries = v;
    }

    public long getMaxBodyChars() {
        return maxBodyChars;
    }

    public void setMaxBodyChars(long v) {
        if (v < 1) {
            throw new IllegalArgumentException(
                    "tessary.prompts.fetch-cache.max-body-chars must be at least 1, not " + v);
        }
        this.maxBodyChars = v;
    }
}
