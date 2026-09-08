// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert;

import org.jspecify.annotations.Nullable;

/**
 * A fired-alert record — the thing the delivery channels deliver. Produced by the
 * {@link AlertWorker} when a threshold breaches or a scheduled roll-up is due, and published as an
 * {@link AlertFiredEvent} after the row commits. Idempotent on {@code (alertRuleId, windowStart)} so
 * re-evaluating a window across N backends can never double-fire.
 *
 * <p>{@code alertRuleId} is a REAL foreign key to {@code alert_rule}. {@code ruleType} is denormalized off the rule so
 * renderers ({@link ai.tessary.evals.alert.channel.AlertPayload}) need no join. {@code classifierId} is set
 * only for a threshold firing.
 *
 * @param state the firing's lifecycle state ({@code firing} today; {@code resolved} reserved for
 *     stateful anomaly rules).
 * @param windowStart the inclusive start of the firing window (ISO) — the idempotency discriminant.
 * @param value the basis count that fired (threshold) or the total events rolled up (roll-up).
 * @param threshold the configured threshold at fire time (threshold kind only).
 * @param payloadJson the bounded rolled-up body; never a full trace payload.
 * @param caseId the case this firing is about, for {@link AlertRuleRow.RuleType#CASE_OPENED} and null for
 *     every window-shaped rule type. It is the SECOND idempotency discriminant, not a decoration: a
 *     case-opened rule fires once per case, and two cases opening in the same second share a
 *     {@code windowStart}, so keying those firings on the window alone would silently drop the later one.
 */
public record AlertEventRow(
        String id,
        String projectId,
        String alertRuleId,
        @Nullable String classifierId,
        String ruleType,
        @Nullable String basis,
        String state,
        String windowStart,
        String windowEnd,
        @Nullable Integer value,
        @Nullable Integer threshold,
        @Nullable String payloadJson,
        @Nullable String caseId,
        String occurredAt,
        String createdAt) {

    /** The firing lifecycle state. */
    public static final class State {
        private State() {}

        /** The alert is currently firing. */
        public static final String FIRING = "firing";

        /** The alert condition has cleared (reserved for stateful anomaly rules). */
        public static final String RESOLVED = "resolved";
    }
}
