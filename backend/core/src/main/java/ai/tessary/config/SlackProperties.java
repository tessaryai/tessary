// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The Slack adapter's address and shared key, bound from {@code tessary.slack.*}; Slack's own credentials live in the
 * adapter. Off unless both are set. Whether an org may use Slack is {@code Capability.SLACK}, a separate question.
 */
@ConfigurationProperties(prefix = "tessary.slack")
public record SlackProperties(

        /** Blank turns the surface off. */
        @Nullable String baseUrl,

        /** Env-injected, never committed. Symmetric: one private link between two of our own processes. */
        @Nullable String serviceKey,

        @DefaultValue("20") long timeoutSeconds) {

    /** A partial config is off: an address with no key would always be rejected. */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && serviceKey != null && !serviceKey.isBlank();
    }
}
