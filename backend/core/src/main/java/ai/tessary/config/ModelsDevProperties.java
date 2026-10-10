// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Where the sandbox lanes read models.dev's rates from, bound from {@code tessary.models-dev.*}. The
 * sole reader is {@code llm/ModelsDevRates}: it fetches {@link #getUrl()} at most once per {@link
 * #getRefreshInterval()} and falls back to the copy bundled in the jar whenever the fetch fails, so a
 * models.dev outage never stops a run or leaves it unpriced.
 */
@Component
@ConfigurationProperties(prefix = "tessary.models-dev")
public class ModelsDevProperties {

    /** The live file. Blank never fetches, so every run prices from the bundled copy. */
    private String url = "https://models.dev/api.json";

    /** How long a fetched file is served before the next run fetches again; OpenCode refreshes on the same cadence. */
    private Duration refreshInterval = Duration.ofMinutes(60);

    /** Ceiling on one fetch. A run waits at most this long before it falls back to the bundled copy. */
    private Duration fetchTimeout = Duration.ofSeconds(3);

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    public void setRefreshInterval(Duration refreshInterval) {
        this.refreshInterval = refreshInterval;
    }

    public Duration getFetchTimeout() {
        return fetchTimeout;
    }

    public void setFetchTimeout(Duration fetchTimeout) {
        this.fetchTimeout = fetchTimeout;
    }
}
