// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import java.util.Locale;

/**
 * Alert transports, each with exactly one {@link ai.tessary.alert.channel.AlertChannel} bean resolved by
 * {@link ChannelFactory}. Same {@code fromWire}/{@code wire} contract as {@code git/GitProvider}.
 */
public enum AlertChannelKind {
    SLACK,
    WEBHOOK,
    SENTRY,
    LINEAR,
    PAGERDUTY;

    public static AlertChannelKind fromWire(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "slack" -> SLACK;
            case "webhook" -> WEBHOOK;
            case "sentry" -> SENTRY;
            case "linear" -> LINEAR;
            case "pagerduty" -> PAGERDUTY;
            default -> throw new IllegalArgumentException("unsupported alert channel kind: " + s);
        };
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
