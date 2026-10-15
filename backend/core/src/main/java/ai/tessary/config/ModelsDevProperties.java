// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the sandbox lanes read models.dev's rates from, bound from {@code tessary.models-dev.*}. The
 * sole reader is {@code llm/ModelsDevRates}: it fetches {@link #url()} at most once per {@link
 * #refreshInterval()} and falls back to the copy bundled in the jar whenever the fetch fails, so a
 * models.dev outage never stops a run or leaves it unpriced.
 */
@ConfigurationProperties(prefix = "tessary.models-dev")
public record ModelsDevProperties(

        /** The live file. Blank never fetches, so every run prices from the bundled copy. */
        @DefaultValue("https://models.dev/api.json") String url,

        /** How long a fetched file is served before the next run fetches again; OpenCode refreshes on the same cadence. */
        @DefaultValue("60m") Duration refreshInterval,

        /** Ceiling on one fetch. A run waits at most this long before it falls back to the bundled copy. */
        @DefaultValue("3s") Duration fetchTimeout) {}
