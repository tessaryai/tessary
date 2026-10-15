// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The Slack adapter's address and shared key, bound from {@code tessary.slack.*}; Slack's own credentials live in the
 * adapter. Off unless both are set. Whether an org may use Slack is {@code Capability.SLACK}, a separate question.
 */
@Component
@ConfigurationProperties(prefix = "tessary.slack")
public class SlackProperties {

    /** Blank turns the surface off. */
    private @Nullable String baseUrl;

    /** Env-injected, never committed. Symmetric: one private link between two of our own processes. */
    private @Nullable String serviceKey;

    private long timeoutSeconds = 20;

    /** A partial config is off: an address with no key would always be rejected. */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && serviceKey != null && !serviceKey.isBlank();
    }

    public @Nullable String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(@Nullable String v) {
        this.baseUrl = v;
    }

    public @Nullable String getServiceKey() {
        return serviceKey;
    }

    public void setServiceKey(@Nullable String v) {
        this.serviceKey = v;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(long v) {
        this.timeoutSeconds = v;
    }
}
