// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** HTTP-shaped DTOs for the alert-channels CRUD API. */
public final class AlertChannelDtos {

    private AlertChannelDtos() {}

    /**
     * Create or replace a channel. {@code config} is the kind-specific target + credentials (e.g.
     * {@code {"url": "…"}} for webhook/slack, {@code {"routing_key": "…"}} for pagerduty) — it is sealed
     * with SecretBox and NEVER returned. On update, an absent/empty {@code config} keeps the stored
     * secret.
     *
     * <p>A {@code Map} rather than a {@code JsonNode}: the HTTP message converter is Jackson 3
     * ({@code tools.jackson}) while the injected {@code ObjectMapper} the rest of this codebase writes
     * against is Jackson 2, so a {@code com.fasterxml} {@code JsonNode} on a request body is a type the
     * converter cannot construct — every create/update 500'd on it.
     */
    public record UpsertChannelRequest(
            @NotBlank String kind,
            @NotBlank String name,
            @Nullable Boolean enabled,
            @Nullable Map<String, Object> config) {}

    /**
     * The safe view of a channel: never includes the sealed config/credentials, only a
     * {@code credentialsSet} indicator — mirrors {@code git/GitIntegrationDtos.GitIntegrationView}'s
     * credential omission.
     */
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
                    r.id(),
                    r.kind(),
                    r.name(),
                    r.enabled(),
                    r.configEnc() != null && !r.configEnc().isBlank(),
                    r.createdAt(),
                    r.updatedAt());
        }
    }

    public record DeleteResponse(boolean deleted) {}

    /** A delivery-attempt log entry, so operators can see why a channel isn't receiving alerts. */
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
