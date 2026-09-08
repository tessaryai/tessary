// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert.channel;

import ai.tessary.evals.alert.AlertChannelKind;
import ai.tessary.evals.alert.AlertEventRow;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The delivery SPI: one implementation per {@link AlertChannelKind}, each rendering a fired
 * {@link AlertEventRow} onto an external transport (Slack incoming webhook, a generic signed webhook,
 * or a Sentry/Linear/PagerDuty connector). Spring injects every bean into {@code ChannelFactory},
 * which resolves the right one by {@link #kind()} at fan-out time — exactly the {@code git/GitProvider}
 * + {@code GitProviderFactory} pattern.
 *
 * <p>Implementations MUST be best-effort and non-throwing for transport errors: a 4xx/5xx or a network
 * failure is reported via {@link DeliveryResult#failure} (logged to the delivery-attempt table), never a
 * thrown exception that would abort the fan-out to sibling channels. A bad config (missing URL,
 * unparseable credential) is the one case worth throwing — it is a setup error, not a transport blip.
 *
 * <p>Every implementation that posts to a <em>user-supplied</em> URL MUST re-run
 * {@code UrlGuard.requirePublicHttp} on every send (via {@link ChannelHttp}) — the URL is validated
 * at config time, but DNS can rebind to an internal address (IMDS / RFC1918) before the send.
 *
 * <p>The native Slack-app path can register as a second SLACK-typed seam later; until then
 * {@link SlackChannel} delivers via a self-contained Slack incoming webhook.
 */
public interface AlertChannel {

    AlertChannelKind kind();

    /**
     * Deliver one fired alert to this channel.
     *
     * @param event the persisted, committed fired-alert record to render.
     * @param config the channel's decrypted config JSON (SecretBox-opened by the caller) — the
     *     target URL and any credentials, in the shape this impl owns.
     * @return a {@link DeliveryResult} describing the transport outcome; never null.
     */
    DeliveryResult deliver(AlertEventRow event, JsonNode config);
}
