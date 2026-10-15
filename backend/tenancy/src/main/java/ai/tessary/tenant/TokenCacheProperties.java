// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Bounds for {@link VerifiedTokenCache}. Lives in {@code tenant} so it does not import from {@code auth}, which already
 * depends on this package; the key stays {@code tessary.auth.token-cache.*}.
 */
@Component
@ConfigurationProperties(prefix = "tessary.auth.token-cache")
public class TokenCacheProperties {

    /** Kill switch: false restores one {@code findByPrefix}, bcrypt and {@code last_used_at} write per request. */
    private boolean enabled = true;

    /**
     * Not the revocation window: every revocation path evicts. The TTL bounds only out-of-band changes (a hand edit, a
     * restore, another replica).
     */
    private long ttlSeconds = 60;

    /** Past it the least recently used entry is evicted; an evicted token pays bcrypt again. */
    private int maxEntries = 4096;

    /**
     * Short because a row can be issued or restored meanwhile, and a stale rejection would lock out a now-valid token.
     */
    private long negativeTtlSeconds = 10;

    /** Capped separately from {@link #maxEntries}; see {@link VerifiedTokenCache}. */
    private int maxRejections = 1024;

    /**
     * Zero writes every request. A per-request {@code UPDATE} of one row serialised concurrent pushes on its lock
     * (61,845 updates against one row in a measurement run).
     */
    private long lastUsedWriteIntervalSeconds = 60;

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
        this.ttlSeconds = v;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int v) {
        if (v < 1) {
            throw new IllegalArgumentException("tessary.auth.token-cache.max-entries must be at least 1, not " + v);
        }
        this.maxEntries = v;
    }

    public long getNegativeTtlSeconds() {
        return negativeTtlSeconds;
    }

    public void setNegativeTtlSeconds(long v) {
        this.negativeTtlSeconds = v;
    }

    public int getMaxRejections() {
        return maxRejections;
    }

    public void setMaxRejections(int v) {
        if (v < 1) {
            throw new IllegalArgumentException("tessary.auth.token-cache.max-rejections must be at least 1, not " + v);
        }
        this.maxRejections = v;
    }

    public long getLastUsedWriteIntervalSeconds() {
        return lastUsedWriteIntervalSeconds;
    }

    public void setLastUsedWriteIntervalSeconds(long v) {
        this.lastUsedWriteIntervalSeconds = v;
    }
}
