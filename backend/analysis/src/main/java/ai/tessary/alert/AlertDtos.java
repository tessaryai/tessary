// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;
import org.jspecify.annotations.Nullable;

/** Wire DTOs for the unified alert-rule feature. Snake_case on the wire, camelCase in Java. */
public final class AlertDtos {

    private AlertDtos() {}

    /** A unified alert rule as exposed to the UI / API (both grains; grain fields are null when N/A). */
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
            /** Cadence and quiet hours. Null for every rule type other than {@code case_opened}, whose
             *  schedule is a policy rather than a cron. */
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
     * When a partner is willing to hear from us — launch requirement I3, and the whole configuration
     * surface of a case-opened rule.
     *
     * @param cadenceSeconds minimum spacing between deliveries; 0 is every heartbeat.
     * @param quietFrom local {@code HH:mm} the quiet window opens, or null for none.
     * @param quietTo local {@code HH:mm} it closes. A window that wraps midnight is normal.
     * @param quietZone the IANA zone the two times are read in. Required with a window, because "22:00"
     *     with no zone is not a time.
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
     * Create or replace an alert rule.
     *
     * <p>Named {@code UpsertAlertRuleRequest} rather than {@code UpsertRuleRequest} because springdoc keys
     * the OpenAPI component map on the SIMPLE class name: the redaction slice has a request record by that
     * name too, and the two collapsed into one component — so the generated client typed this endpoint's
     * body as a redaction rule (a regex and a replacement string) and typed nothing here at all.
     *
     * <p>{@code ruleType} selects the grain: {@code threshold} needs
     * {@code classifier_id} + {@code basis} (+ optional {@code threshold}/{@code window_seconds}); {@code digest}
     * / {@code brief} need a cron ({@code digest} defaults to the server cron; {@code brief} requires
     * {@code brief_cron}).
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
            /** Cadence and quiet hours, for a {@code case_opened} rule. Absent means every tick, no
             *  quiet window — which is what a partner gets until they say otherwise. */
            @Nullable PolicyView policy) {

        public int thresholdOrDefault() {
            return threshold == null ? 1 : threshold;
        }

        public int windowSecondsOrDefault() {
            return windowSeconds == null ? 86_400 : windowSeconds; // default rolling 24h
        }

        /** The request's policy, or {@link AlertPolicy#IMMEDIATE} — through the same lenient parser the
         *  stored blob goes through, so a policy cannot mean one thing saved and another read back. */
        public AlertPolicy policyOrDefault() {
            PolicyView p = policy;
            return p == null
                    ? AlertPolicy.IMMEDIATE
                    : AlertPolicy.of(p.cadenceSeconds(), p.quietFrom(), p.quietTo(), p.quietZone());
        }
    }

    /** Toggle the hard enable/disable lifecycle of a rule. */
    public record SetEnabledRequest(@NotNull Boolean enabled) {}

    /** Soft-mute a rule until an ISO instant; null/absent clears the snooze. */
    public record SnoozeRequest(
            @JsonProperty("snoozed_until") @Nullable String snoozedUntil) {}

    /** A fired-alert record. */
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
