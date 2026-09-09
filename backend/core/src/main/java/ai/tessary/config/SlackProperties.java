// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How the backend reaches the Slack adapter service, bound from {@code tessary.slack.*}.
 *
 * <p>These aren't Slack's own credentials: the signing secret, the bot token and the Web API base live
 * in the adapter. This holds only the adapter's address and the key the two use to authenticate to
 * each other.
 *
 * <p>Default-off: with no base URL and key configured, the Slack path is inert, no outbound call is
 * attempted and the mention callback authorizes nobody. A deployment opts in by pointing at the service
 * and injecting the shared key.
 *
 * <p>Per-tenant routing (which project a workspace's {@code @mention} resolves to, which channel
 * receives digests) lives in {@code slack_install}; whether an organization may use Slack at all is
 * {@code Capability.SLACK}, a different question from whether the adapter is deployed.
 */
@Component
@ConfigurationProperties(prefix = "tessary.slack")
public class SlackProperties {

    /** Where {@code slack-service} is reachable, e.g. {@code http://slack:8090}. Blank → the surface is OFF. */
    private @Nullable String baseUrl;

    /**
     * The shared key both directions of the link authenticate with; env-injected, never committed. The
     * backend presents it on {@code POST /deliver}, and the adapter presents the same one on the mention
     * callback. Symmetric because it is one private link between two of our own processes, the same
     * posture {@code classify-service} takes with its API key.
     */
    private @Nullable String serviceKey;

    /** How long to wait on the adapter before giving up on a delivery. */
    private long timeoutSeconds = 20;

    /**
     * The adapter is reachable (and therefore the Slack surface is ON at the deploy level) iff both are
     * present. An address with no key would be a call that is always rejected, so a partial config is
     * treated as off rather than half-working.
     */
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
