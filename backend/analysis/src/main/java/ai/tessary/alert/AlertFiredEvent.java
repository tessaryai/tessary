// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

/**
 * Published in-process after an {@link AlertEventRow} commits — the delivery seam the alert
 * channels (Slack/webhook/Sentry/Linear/PagerDuty) listen on. The worker PERSISTS the {@code alert_event}
 * row and publishes this event; it wires NO external transport. A consumer registers an
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)} so it only delivers
 * persisted firings.
 *
 * <p>Published ONLY when the row was newly written ({@code insertIfAbsent} returned {@code true}), so a
 * window re-evaluated across N backends fires the event exactly once.
 *
 * @param row the fired-alert record, fully populated and persisted.
 */
public record AlertFiredEvent(AlertEventRow row) {

    public String projectId() {
        return row.projectId();
    }

    public String ruleType() {
        return row.ruleType();
    }
}
