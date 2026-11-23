// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the fix step, bound from {@code tessary.fix.*}. The worker's tick is {@code tessary.fix.heartbeat-ms}. The
 * agent reads code, edits it and runs the repository's checks, so it gets more time and turns than RCA.
 */
@ConfigurationProperties(prefix = "tessary.fix")
public record FixProperties(
        /** The session's wall clock, start and clone included. */
        @DefaultValue("1800000") long timeoutMs,

        /** Two turns go to the answer, so the session's tool cap is two below this. */
        @DefaultValue("80") int maxTurns) {}
