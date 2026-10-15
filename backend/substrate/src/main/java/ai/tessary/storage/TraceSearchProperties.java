// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for trace search over span payloads ({@code tessary.traces.search.*}). Below {@link #walkThreshold} matches
 * the payload index lists every match; at it, walking the newest traces finds a page sooner. {@link #timeoutMs} makes a
 * too-broad search fail clearly instead of holding a connection.
 */
@Component
@ConfigurationProperties(prefix = "tessary.traces.search")
public class TraceSearchProperties {

    /**
     * At 50,000, listing every match costs a few hundred ms, while the walk is slow when a common word's matches are
     * all old.
     */
    private int walkThreshold = 50_000;

    private long timeoutMs = 5_000;

    public int getWalkThreshold() {
        return walkThreshold;
    }

    public void setWalkThreshold(int v) {
        this.walkThreshold = v;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long v) {
        this.timeoutMs = v;
    }
}
