// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert;

/**
 * A project's binding to one delivery channel for fired alerts. Many channels per project,
 * each fanned out to by {@link ai.tessary.evals.alert.channel.AlertDeliveryListener} when an
 * {@link AlertFiredEvent} commits.
 *
 * <p>{@code configEnc} holds SecretBox-sealed (AES-256-GCM, {@code evals.secret-key}) channel config
 * JSON — the target URL plus any credential (webhook signing secret, Sentry DSN, Linear API key + team
 * id, PagerDuty routing key). It is NEVER returned over the API (see {@code AlertChannelDtos.ChannelView});
 * only a {@code credentialsSet} indicator is surfaced. The shape of the sealed JSON is owned by each
 * {@code AlertChannel} impl.
 */
public record AlertChannelRow(
        String id,
        String projectId,
        String kind,
        String name,
        boolean enabled,
        String configEnc,
        String attributes,
        String createdAt,
        String updatedAt) {

    public AlertChannelKind kindEnum() {
        return AlertChannelKind.fromWire(kind);
    }
}
