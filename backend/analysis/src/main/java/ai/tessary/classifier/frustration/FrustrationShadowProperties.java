// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.frustration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The deployment's shadow decision model for Frustration, bound from {@code tessary.frustration.shadow.*}:
 * a second {@code /v1/systemone} server every Jev-scored turn is re-sent to, with both answers kept
 * side by side ({@code frustration_shadow}) and nothing a customer sees changed. Blank {@code url}, the
 * default, means no shadow and the sweep never runs.
 *
 * <p>The server may be asleep most of the time (the cloud's wakes hourly and stops when idle): the sweep
 * probes {@code /health} first and does nothing while it does not answer, so the backlog simply waits.
 */
@ConfigurationProperties(prefix = "tessary.frustration.shadow")
public record FrustrationShadowProperties(

        /** Base URL of the shadow server; {@code /v1/systemone} and {@code /health} are appended. Blank disables. */
        @DefaultValue("") String url,

        /** Bearer key sent with each request; blank sends none (the cloud's Eikos takes none). */
        @DefaultValue("") String apiKey,

        /** The {@code model} field of each request; whatever the server expects, it is not a routing choice here. */
        @DefaultValue("decider") String model,

        /** How often the sweep runs, in milliseconds. Must stay under the server's idle stop. */
        @DefaultValue("300000") long sweepMs,

        /** Turns fetched and sent per batch; the sweep keeps going batch by batch until the backlog is empty. */
        @DefaultValue("100") int batch,

        /** Per-request timeout, in milliseconds. */
        @DefaultValue("30000") long timeoutMs,

        /** The most one sweep runs before it yields, in milliseconds; the next sweep carries on. */
        @DefaultValue("1200000") long budgetMs) {

    /** Whether a shadow server is configured at all. */
    public boolean enabled() {
        return url != null && !url.isBlank();
    }

    /** The base URL without a trailing slash. */
    public String baseUrl() {
        String u = url.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }
}
