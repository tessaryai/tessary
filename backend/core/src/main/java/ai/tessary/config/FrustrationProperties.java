// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Deployment bounds on Frustration's decision-model calls ({@code tessary.frustration.*}). The operating point is
 * per-project {@code config_json}.
 */
@Component
@ConfigurationProperties(prefix = "tessary.frustration")
public class FrustrationProperties {

    /** Neither provider documents a rate limit; this bounds the burst. */
    private int concurrency = 4;

    private long timeoutMs = 20_000;

    /** Including the first, on a 429, a 5xx or a transport failure. */
    private int maxAttempts = 3;

    /** Holds of a mostly failed page before it is skipped. */
    private int pageRetries = 3;

    /** Seconds a paused classifier waits before checking the org's key again. */
    private long credentialRetrySeconds = 1_800;

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int getPageRetries() {
        return pageRetries;
    }

    public void setPageRetries(int pageRetries) {
        this.pageRetries = pageRetries;
    }

    public long getCredentialRetrySeconds() {
        return credentialRetrySeconds;
    }

    public void setCredentialRetrySeconds(long credentialRetrySeconds) {
        this.credentialRetrySeconds = credentialRetrySeconds;
    }
}
