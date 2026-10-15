// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the live per-provider model catalog fetch, bound from
 * {@code tessary.model-catalog.*}. Each provider's model list is fetched from that provider's own
 * API, cached by {@code (provider, region)}, and refreshed on this TTL — see {@code
 * ModelCatalogFetchService}, the sole reader.
 *
 * <p>The two numbers below trade off in opposite directions: a longer refresh interval means fewer
 * outbound calls to every configured vendor's API (this runs behind both the settings page and,
 * cache-only, the hot judge-call path), while a shorter fetch timeout means a slow or hanging vendor
 * degrades the picker faster rather than blocking a settings-page load for its full network timeout.
 * Defaults are conservative rather than tuned against production traffic, since there is none yet —
 * revisit once the fetch pattern is observable.
 */
@ConfigurationProperties(prefix = "tessary.model-catalog")
public record ModelCatalogProperties(

        /** How long a fetched (provider, region) entry is served before the next read triggers a refetch. */
        @DefaultValue("15m") Duration refreshInterval,

        /** Per-call ceiling on a single provider's models-endpoint request. */
        @DefaultValue("5s") Duration fetchTimeout) {}
