// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the live per-provider model catalog fetch (#939 TASK 2), bound from
 * {@code evals.model-catalog.*}. Each provider's model list is fetched from that provider's own
 * API, cached by {@code (provider, region)}, and refreshed on this TTL — see {@code
 * ModelCatalogFetchService}, the sole reader.
 *
 * <p>The two numbers below trade off in opposite directions: a longer refresh interval means fewer
 * outbound calls to every configured vendor's API (this runs behind both the settings page and,
 * cache-only, the hot judge-call path), while a shorter fetch timeout means a slow or hanging vendor
 * degrades the picker faster rather than blocking a settings-page load for its full network timeout.
 * Defaults are conservative rather than tuned against production traffic, since there is none yet —
 * revisit once #939 ships and the fetch pattern is observable.
 */
@Component
@ConfigurationProperties(prefix = "evals.model-catalog")
public class ModelCatalogProperties {

    /** How long a fetched (provider, region) entry is served before the next read triggers a refetch. */
    private Duration refreshInterval = Duration.ofMinutes(15);

    /** Per-call ceiling on a single provider's models-endpoint request. */
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
