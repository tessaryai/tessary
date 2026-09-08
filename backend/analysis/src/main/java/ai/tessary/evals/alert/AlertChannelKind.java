// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert;

import java.util.Locale;

/**
 * The transports a fired alert can be delivered to. Each value has exactly one
 * {@link ai.tessary.evals.alert.channel.AlertChannel} SPI bean, resolved by {@link ChannelFactory};
 * adding a channel is just adding its impl class. Mirrors {@code git/GitProvider}'s
 * {@code fromWire}/{@code wire} contract.
 *
 * <ul>
 *   <li>{@link #SLACK} — a Slack <em>incoming webhook</em> (outbound POST to a user-configured Slack
 *       webhook URL).
 *   <li>{@link #WEBHOOK} — a generic signed outbound POST with a stable, versioned payload schema.
 *   <li>{@link #SENTRY} — creates a Sentry issue/event via the Store API (DSN-derived).
 *   <li>{@link #LINEAR} — creates a Linear issue via the GraphQL API.
 *   <li>{@link #PAGERDUTY} — triggers a PagerDuty incident via the Events API v2.
 * </ul>
 */
public enum AlertChannelKind {
    SLACK,
    WEBHOOK,
    SENTRY,
    LINEAR,
    PAGERDUTY;

    public static AlertChannelKind fromWire(String s) {
        if (s == null) throw new IllegalArgumentException("alert channel kind is required");
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
