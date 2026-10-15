// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Deployment bounds on Frustration's decision-model calls ({@code tessary.frustration.*}). The operating point is
 * per-project {@code config_json}.
 */
@ConfigurationProperties(prefix = "tessary.frustration")
public record FrustrationProperties(

        /** Neither provider documents a rate limit; this bounds the burst. */
        @DefaultValue("4") int concurrency,

        @DefaultValue("20000") long timeoutMs,

        /** Including the first, on a 429, a 5xx or a transport failure. */
        @DefaultValue("3") int maxAttempts,

        /** Holds of a mostly failed page before it is skipped. */
        @DefaultValue("3") int pageRetries,

        /** Seconds a paused classifier waits before checking the org's key again. */
        @DefaultValue("1800") long credentialRetrySeconds) {}
