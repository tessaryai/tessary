// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the live per-provider model fetch ({@code tessary.model-catalog.*}), cached by {@code (provider, region)};
 * read only by {@code ModelCatalogFetchService}. Defaults are conservative, not tuned against production traffic.
 */
@ConfigurationProperties(prefix = "tessary.model-catalog")
public record ModelCatalogProperties(

        /** TTL before the next read refetches. */
        @DefaultValue("15m") Duration refreshInterval,

        /** A short timeout lets a hanging vendor degrade the picker instead of blocking a settings-page load. */
        @DefaultValue("5s") Duration fetchTimeout) {}
