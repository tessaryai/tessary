// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class AlertChannelDtos {

    private AlertChannelDtos() {}

    /**
     * {@code config} is sealed with SecretBox and never returned; an absent or empty one on update keeps the stored
     * secret. A {@code Map}, not {@code JsonNode}: the HTTP converter is Jackson 3, and a Jackson 2 {@code JsonNode}
     * body cannot be constructed.
     */
    public record UpsertChannelRequest(
            @NotBlank String kind,
            @NotBlank String name,
            @Nullable Boolean enabled,
            @Nullable Map<String, Object> config) {}

    /** Never includes the sealed config, only {@code credentialsSet}. */
    public record ChannelView(
            String id,
            String kind,
            String name,
            boolean enabled,
            boolean credentialsSet,
            String createdAt,
            String updatedAt) {
        public static ChannelView from(AlertChannelRow r) {
            return new ChannelView(
                    r.id(), r.kind(), r.name(), r.enabled(), !r.configEnc().isBlank(), r.createdAt(), r.updatedAt());
        }
    }

    public record DeleteResponse(boolean deleted) {}

    /** So operators can see why a channel isn't receiving alerts. */
    public record DeliveryAttemptView(
            String id,
            String alertEventId,
            String channelId,
            String status,
            @Nullable Integer httpStatus,
            @Nullable String error,
            String attemptedAt,
            @Nullable String completedAt) {
        public static DeliveryAttemptView from(DeliveryAttemptRow r) {
            return new DeliveryAttemptView(
                    r.id(),
                    r.alertEventId(),
                    r.channelId(),
                    r.status(),
                    r.httpStatus(),
                    r.error(),
                    r.attemptedAt(),
                    r.completedAt());
        }
    }
}
