// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Where {@code llm/ModelsDevRates} reads models.dev rates. A failed fetch falls back to the jar's bundled copy, so an
 * outage never stops or unprices a run.
 */
@Component
@ConfigurationProperties(prefix = "tessary.models-dev")
public class ModelsDevProperties {

    /** Blank never fetches. */
    private String url = "https://models.dev/api.json";

    /** OpenCode refreshes on the same cadence. */
    private Duration refreshInterval = Duration.ofMinutes(60);

    /** The longest a run waits before falling back. */
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
