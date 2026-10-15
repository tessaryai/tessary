// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import ai.tessary.alert.AlertChannelKind;
import ai.tessary.alert.AlertEventRow;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The delivery SPI, one implementation per {@link AlertChannelKind}, resolved by {@code ChannelFactory}. Transport
 * errors return {@link DeliveryResult#failure} and never throw, so sibling channels still deliver; only bad config
 * throws. Every user-supplied URL is re-checked by {@code UrlGuard.requirePublicHttp} on each send (via
 * {@link ChannelHttp}), since DNS can rebind.
 */
public interface AlertChannel {

    AlertChannelKind kind();

    /** @param config the decrypted config JSON, in the shape this implementation owns @return never null */
    DeliveryResult deliver(AlertEventRow event, JsonNode config);
}
