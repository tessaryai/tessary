// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import org.jspecify.annotations.Nullable;

public final class AlertDtos {

    private AlertDtos() {}

    /** Grain fields are null when N/A. */
    public record AlertRuleView(
            String id,
            @JsonProperty("rule_type") String ruleType,
            String name,
            @JsonProperty("classifier_id") @Nullable String classifierId,
            @Nullable String target,
            @Nullable String basis,
            @Nullable Integer threshold,
            @JsonProperty("window_seconds") @Nullable Integer windowSeconds,
            @JsonProperty("group_by") @Nullable String groupBy,
            @JsonProperty("min_samples") @Nullable Integer minSamples,
            @Nullable String severity,
            @JsonProperty("digest_cron") @Nullable String digestCron,
            @JsonProperty("brief_cron") @Nullable String briefCron,
            boolean enabled,
            @JsonProperty("snoozed_until") @Nullable String snoozedUntil,
            @JsonProperty("last_evaluated_at") @Nullable String lastEvaluatedAt,
            @JsonProperty("last_digest_at") @Nullable String lastDigestAt,
            @JsonProperty("last_brief_at") @Nullable String lastBriefAt,
            /** Null for every rule type other than {@code case_opened}. */
            @Nullable PolicyView policy,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt) {

        public static AlertRuleView of(AlertRuleRow r) {
            return of(r, null);
        }

        public static AlertRuleView of(AlertRuleRow r, @Nullable PolicyView policy) {
            return new AlertRuleView(
                    r.id(),
                    r.ruleType(),
                    r.name(),
                    r.classifierId(),
                    r.target(),
                    r.basis(),
                    r.threshold(),
                    r.windowSeconds(),
                    r.groupBy(),
                    r.minSamples(),
                    r.severity(),
                    r.digestCron(),
                    r.briefCron(),
                    r.enabled(),
                    r.snoozedUntil(),
                    r.lastEvaluatedAt(),
                    r.lastDigestAt(),
                    r.lastBriefAt(),
                    policy,
                    r.createdAt(),
                    r.updatedAt());
        }
    }

    /**
     * @param cadenceSeconds minimum spacing between deliveries; 0 is every heartbeat @param quietTo a window that wraps
     * midnight is normal @param quietZone the IANA zone; required with a window, because a time with no zone is not a
     * time
     */
    public record PolicyView(
            @JsonProperty("cadence_seconds") long cadenceSeconds,
            @JsonProperty("quiet_from") @Nullable String quietFrom,
            @JsonProperty("quiet_to") @Nullable String quietTo,
            @JsonProperty("quiet_zone") String quietZone) {

        public static PolicyView of(AlertPolicy p) {
            LocalTime from = p.quietFrom();
            LocalTime to = p.quietTo();
            return new PolicyView(
                    p.cadenceSeconds(),
                    from == null ? null : from.toString(),
                    to == null ? null : to.toString(),
                    p.zone().getId());
        }
    }

    /**
     * Not {@code UpsertRuleRequest}: springdoc keys components on the simple class name, and the redaction slice's
     * record of that name collapsed with this one in the generated client.
     */
    public record UpsertAlertRuleRequest(
            @JsonProperty("rule_type") @NotBlank String ruleType,
            @NotBlank String name,
            @JsonProperty("classifier_id") @Nullable String classifierId,
            @Nullable String target,
            @Nullable String basis,
            @Nullable Integer threshold,
            @JsonProperty("window_seconds") @Nullable Integer windowSeconds,
            @JsonProperty("group_by") @Nullable String groupBy,
            @JsonProperty("min_samples") @Nullable Integer minSamples,
            @Nullable String severity,
            @JsonProperty("digest_cron") @Nullable String digestCron,
            @JsonProperty("brief_cron") @Nullable String briefCron,
            /** Absent means every tick and no quiet window. */
            @Nullable PolicyView policy) {

        public int thresholdOrDefault() {
            return threshold == null ? 1 : threshold;
        }

        public int windowSecondsOrDefault() {
            return windowSeconds == null ? 86_400 : windowSeconds; // default rolling 24h
        }

        /**
         * Through the same lenient parser as the stored blob, so a policy cannot mean one thing saved and another read
         * back.
         */
        public AlertPolicy policyOrDefault() {
            PolicyView p = policy;
            return p == null
                    ? AlertPolicy.IMMEDIATE
                    : AlertPolicy.of(p.cadenceSeconds(), p.quietFrom(), p.quietTo(), p.quietZone());
        }
    }

    public record SetEnabledRequest(@NotNull Boolean enabled) {}

    /** Null clears the snooze. */
    public record SnoozeRequest(
            @JsonProperty("snoozed_until") @Nullable String snoozedUntil) {}

    public record AlertEventView(
            String id,
            @JsonProperty("alert_rule_id") String alertRuleId,
            @JsonProperty("classifier_id") @Nullable String classifierId,
            @JsonProperty("rule_type") String ruleType,
            @Nullable String basis,
            String state,
            @JsonProperty("window_start") String windowStart,
            @JsonProperty("window_end") String windowEnd,
            @Nullable Integer value,
            @Nullable Integer threshold,
            @JsonProperty("payload_json") @Nullable String payloadJson,
            @JsonProperty("occurred_at") String occurredAt) {

        public static AlertEventView of(AlertEventRow r) {
            return new AlertEventView(
                    r.id(),
                    r.alertRuleId(),
                    r.classifierId(),
                    r.ruleType(),
                    r.basis(),
                    r.state(),
                    r.windowStart(),
                    r.windowEnd(),
                    r.value(),
                    r.threshold(),
                    r.payloadJson(),
                    r.occurredAt());
        }
    }
}
