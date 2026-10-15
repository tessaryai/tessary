// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the live per-provider model fetch ({@code tessary.model-catalog.*}), cached by {@code (provider, region)};
 * read only by {@code ModelCatalogFetchService}. Defaults are conservative, not tuned against production traffic.
 */
@Component
@ConfigurationProperties(prefix = "tessary.model-catalog")
public class ModelCatalogProperties {

    /** TTL before the next read refetches. */
    private Duration refreshInterval = Duration.ofMinutes(15);

    /** A short timeout lets a hanging vendor degrade the picker instead of blocking a settings-page load. */
    private Duration fetchTimeout = Duration.ofSeconds(5);

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
