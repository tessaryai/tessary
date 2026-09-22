// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How the Frustration classifier talks to its decision model, bound from {@code tessary.frustration.*}.
 * The classifier's operating point (its flag threshold and rate-watch dials) is per-project data on the
 * classifier's {@code config_json}; these are the deployment's bounds on the calls themselves.
 *
 * <p>{@code timeoutMs} and {@code maxAttempts} bind the decision client, which serves only this lane.
 */
@Component
@ConfigurationProperties(prefix = "tessary.frustration")
public class FrustrationProperties {

    /** Turns of one page sent at once. Neither provider documents a rate limit; this bounds the burst. */
    private int concurrency = 4;

    /** Per-request timeout for one decision call, in milliseconds. */
    private long timeoutMs = 20_000;

    /** Attempts per turn, the first included, on a 429, a 5xx or a transport failure. */
    private int maxAttempts = 3;

    /** Times a page whose calls mostly failed is held and re-sent before it is skipped. */
    private int pageRetries = 3;

    /** How long a paused classifier waits before it checks the org's key again, in seconds. */
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
