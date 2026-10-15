// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where {@code llm/ModelsDevRates} reads models.dev rates. A failed fetch falls back to the jar's bundled copy, so an
 * outage never stops or unprices a run.
 */
@ConfigurationProperties(prefix = "tessary.models-dev")
public record ModelsDevProperties(

        /** Blank never fetches. */
        @DefaultValue("https://models.dev/api.json") String url,

        /** OpenCode refreshes on the same cadence. */
        @DefaultValue("60m") Duration refreshInterval,

        /** The longest a run waits before falling back. */
        @DefaultValue("3s") Duration fetchTimeout) {}
