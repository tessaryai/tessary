// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import ai.tessary.alert.channel.AlertChannel;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.TessaryException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolves the right {@link AlertChannel} SPI bean by {@link AlertChannelKind}. Spring
 * injects every registered impl, so adding a channel is just adding its {@code @Component} class —
 * nothing here changes. Mirrors {@code git/GitProviderFactory}'s EnumMap-of-beans registry.
 */
@Component
public class ChannelFactory {

    private final Map<AlertChannelKind, AlertChannel> impls = new EnumMap<>(AlertChannelKind.class);

    public ChannelFactory(List<AlertChannel> beans) {
        for (AlertChannel c : beans) impls.put(c.kind(), c);
    }

    public AlertChannel forKind(AlertChannelKind kind) {
        AlertChannel impl = impls.get(kind);
        if (impl == null) {
            throw new TessaryException(AlertError.UNSUPPORTED_CHANNEL, kind.wire());
        }
        return impl;
    }
}
