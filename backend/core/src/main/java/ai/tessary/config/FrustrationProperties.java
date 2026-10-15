// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the Frustration classifier talks to its decision model, bound from {@code tessary.frustration.*}.
 * The classifier's operating point (its flag threshold and rate-watch dials) is per-project data on the
 * classifier's {@code config_json}; these are the deployment's bounds on the calls themselves.
 *
 * <p>{@code timeoutMs} and {@code maxAttempts} bind the decision client, which serves only this lane.
 */
@ConfigurationProperties(prefix = "tessary.frustration")
public record FrustrationProperties(

        /** Turns of one page sent at once. Neither provider documents a rate limit; this bounds the burst. */
        @DefaultValue("4") int concurrency,

        /** Per-request timeout for one decision call, in milliseconds. */
        @DefaultValue("20000") long timeoutMs,

        /** Attempts per turn, the first included, on a 429, a 5xx or a transport failure. */
        @DefaultValue("3") int maxAttempts,

        /** Times a page whose calls mostly failed is held and re-sent before it is skipped. */
        @DefaultValue("3") int pageRetries,

        /** How long a paused classifier waits before it checks the org's key again, in seconds. */
        @DefaultValue("1800") long credentialRetrySeconds) {}
