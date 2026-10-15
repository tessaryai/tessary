// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import org.jspecify.annotations.Nullable;

/**
 * The at-most-once guard: unique on {@code (alert_event_id, channel_id)} and inserted {@code pending} before the HTTP
 * call, so a listener firing twice can never double-send.
 */
public record DeliveryAttemptRow(
        String id,
        String alertEventId,
        String channelId,
        String projectId,
        String status,
        @Nullable Integer httpStatus,
        @Nullable String error,
        String attemptedAt,
        @Nullable String completedAt) {}
