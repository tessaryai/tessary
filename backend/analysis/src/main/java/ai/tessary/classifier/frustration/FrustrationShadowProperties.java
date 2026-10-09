// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The deployment's shadow decision model for Frustration, bound from {@code tessary.frustration.shadow.*}:
 * a second {@code /v1/systemone} server every Jev-scored turn is re-sent to, with both answers kept
 * side by side ({@code frustration_shadow}) and nothing a customer sees changed. Blank {@code url}, the
 * default, means no shadow and the sweep never runs.
 *
 * <p>The server may be asleep most of the time (the cloud's wakes hourly and stops when idle): the sweep
 * probes {@code /health} first and does nothing while it does not answer, so the backlog simply waits.
 */
@Component
@ConfigurationProperties(prefix = "tessary.frustration.shadow")
public class FrustrationShadowProperties {

    /** Base URL of the shadow server; {@code /v1/systemone} and {@code /health} are appended. Blank disables. */
    private String url = "";

    /** Bearer key sent with each request; blank sends none (the cloud's Eikos takes none). */
    private String apiKey = "";

    /** The {@code model} field of each request; whatever the server expects, it is not a routing choice here. */
    private String model = "decider";

    /** How often the sweep runs, in milliseconds. Must stay under the server's idle stop. */
    private long sweepMs = 300_000;

    /** Turns fetched and sent per batch; the sweep keeps going batch by batch until the backlog is empty. */
    private int batch = 100;

    /** Per-request timeout, in milliseconds. */
    private long timeoutMs = 30_000;

    /** The most one sweep runs before it yields, in milliseconds; the next sweep carries on. */
    private long budgetMs = 1_200_000;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public long getSweepMs() {
        return sweepMs;
    }

    public void setSweepMs(long sweepMs) {
        this.sweepMs = sweepMs;
    }

    public int getBatch() {
        return batch;
    }

    public void setBatch(int batch) {
        this.batch = batch;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public long getBudgetMs() {
        return budgetMs;
    }

    public void setBudgetMs(long budgetMs) {
        this.budgetMs = budgetMs;
    }

    /** Whether a shadow server is configured at all. */
    public boolean enabled() {
        return url != null && !url.isBlank();
    }

    /** The base URL without a trailing slash. */
    public String baseUrl() {
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
