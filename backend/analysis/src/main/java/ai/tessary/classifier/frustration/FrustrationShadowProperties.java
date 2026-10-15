// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The shadow decision model for Frustration ({@code tessary.frustration.shadow.*}): a second {@code /v1/systemone}
 * server every scored turn is re-sent to, invisible to customers. Blank {@code url} disables it. The server may be
 * asleep, so the sweep probes {@code /health} first and the backlog waits.
 */
@Component
@ConfigurationProperties(prefix = "tessary.frustration.shadow")
public class FrustrationShadowProperties {

    /** {@code /v1/systemone} and {@code /health} are appended. Blank disables. */
    private String url = "";

    /** Blank sends none. */
    private String apiKey = "";

    /** Whatever the server expects; not a routing choice here. */
    private String model = "decider";

    /** Must stay under the server's idle stop. */
    private long sweepMs = 300_000;

    /** The sweep continues batch by batch until the backlog is empty. */
    private int batch = 100;

    private long timeoutMs = 30_000;

    /** The next sweep carries on. */
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

    public boolean enabled() {
        return url != null && !url.isBlank();
    }

    public String baseUrl() {
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
