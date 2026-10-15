// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The shadow decision model for Frustration ({@code tessary.frustration.shadow.*}): a second {@code /v1/systemone}
 * server every scored turn is re-sent to, invisible to customers. Blank {@code url} disables it. The server may be
 * asleep, so the sweep probes {@code /health} first and the backlog waits.
 */
@ConfigurationProperties(prefix = "tessary.frustration.shadow")
public record FrustrationShadowProperties(

        /** {@code /v1/systemone} and {@code /health} are appended. Blank disables. */
        @DefaultValue("") String url,

        /** Blank sends none. */
        @DefaultValue("") String apiKey,

        /** Whatever the server expects; not a routing choice here. */
        @DefaultValue("decider") String model,

        /** Must stay under the server's idle stop. */
        @DefaultValue("300000") long sweepMs,

        /** The sweep continues batch by batch until the backlog is empty. */
        @DefaultValue("100") int batch,

        @DefaultValue("30000") long timeoutMs,

        /** The next sweep carries on. */
        @DefaultValue("1200000") long budgetMs) {

    public boolean enabled() {
        return url != null && !url.isBlank();
    }

    public String baseUrl() {
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
