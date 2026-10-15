// SPDX-License-Identifier: Apache-2.0
package ai.tessary.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
@ConfigurationProperties(prefix = "tessary.slack")
public record SlackProperties(

        /** Where {@code slack-service} is reachable, e.g. {@code http://slack:8090}. Blank → the surface is OFF. */
        @Nullable String baseUrl,

        /**
         * The shared key both directions of the link authenticate with; env-injected, never committed. The
         * backend presents it on {@code POST /deliver}, and the adapter presents the same one on the mention
         * callback. Symmetric because it is one private link between two of our own processes, the same
         * posture the groundedness model server takes with its API key.
         */
        @Nullable String serviceKey,

        /** How long to wait on the adapter before giving up on a delivery. */
        @DefaultValue("20") long timeoutSeconds) {

    /**
     * The adapter is reachable (and therefore the Slack surface is ON at the deploy level) iff both are
     * present. An address with no key would be a call that is always rejected, so a partial config is
     * treated as off rather than half-working.
     */
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && serviceKey != null && !serviceKey.isBlank();
    }
}
