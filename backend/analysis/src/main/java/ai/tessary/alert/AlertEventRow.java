// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import org.jspecify.annotations.Nullable;

/**
 * A fired alert, published as {@link AlertFiredEvent} after commit and idempotent on
 * {@code (alertRuleId, windowStart)}. {@code ruleType} is denormalized so renderers need no join. @param payloadJson
 * bounded; never a full trace payload @param caseId set for {@link AlertRuleRow.RuleType#CASE_OPENED}; a second
 * idempotency discriminant, since two cases opening in one second share a {@code windowStart}
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

    public static final class State {
        private State() {}

        public static final String FIRING = "firing";
    }
}
