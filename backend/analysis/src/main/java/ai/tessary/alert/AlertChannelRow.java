// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

/**
 * A project's binding to one alert delivery channel. {@code configEnc} is SecretBox-sealed config JSON (target and
 * credentials), never returned over the API; each {@code AlertChannel} owns its shape.
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
