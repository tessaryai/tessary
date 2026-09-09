// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import org.jspecify.annotations.Nullable;

/**
 * One outbound-delivery attempt of a fired alert to one channel — the audit trail and the
 * at-most-once guard. Keyed on {@code (alert_event_id, channel_id)} (UNIQUE): the row
 * is inserted {@code pending} BEFORE the HTTP call, so a listener that fires twice (Spring
 * {@code fallbackExecution=true}, a reclaim) can never double-send; it is UPDATEd to {@code delivered}
 * or {@code failed} with the outcome after the call returns.
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
        @Nullable String completedAt) {

    public static final class Status {
        private Status() {}

        public static final String PENDING = "pending";
        public static final String DELIVERED = "delivered";
        public static final String FAILED = "failed";
    }
}
