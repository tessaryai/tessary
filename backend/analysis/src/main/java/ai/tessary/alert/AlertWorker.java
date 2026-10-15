// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import ai.tessary.classifier.ClassifierService;
import ai.tessary.config.AlertProperties;
import ai.tessary.config.TraceMdcBridge;
import ai.tessary.open.obs.LogContext;
import ai.tessary.open.obs.Markers;
import ai.tessary.plan.Capability;
import ai.tessary.plan.CapabilityService;
import ai.tessary.tenant.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Fires due roll-up rules and case-opened notifications on each heartbeat, off the ingest hot path. The idempotent
 * {@code (alert_rule_id, window_start)} insert guards against double-firing across instances.
 */
@Component
public class AlertWorker {

    private static final Logger log = LoggerFactory.getLogger(AlertWorker.class);

    private final AlertRuleRepository rules;
    private final AlertEventRepository events;
    private final AlertAssembler assembler;
    private final CaseAlertEvaluator caseAlerts;
    private final ObjectMapper mapper;
    private final ClassifierService classifiers;

    private final AlertProperties props;
    private final ApplicationEventPublisher publisher;
    private final CapabilityService capabilities;
    private final ProjectRepository projects;
    private final TraceMdcBridge traceBridge;

    public AlertWorker(
            AlertRuleRepository rules,
            AlertEventRepository events,
            AlertAssembler assembler,
            CaseAlertEvaluator caseAlerts,
            ObjectMapper mapper,
            ClassifierService classifiers,
            AlertProperties props,
            ApplicationEventPublisher publisher,
            CapabilityService capabilities,
            ProjectRepository projects,
            TraceMdcBridge traceBridge) {
        this.rules = rules;
        this.events = events;
        this.assembler = assembler;
        this.caseAlerts = caseAlerts;
        this.mapper = mapper;
        this.classifiers = classifiers;
        this.props = props;
        this.publisher = publisher;
        this.capabilities = capabilities;
        this.projects = projects;
        this.traceBridge = traceBridge;
    }

    /** An unknown project is treated as not entitled. */
    private boolean entitledForAlerts(String projectId) {
        return projects.findById(projectId)
                .map(p -> capabilities.isEnabled(p.orgId(), Capability.ALERTS))
                .orElse(false);
    }

    @Scheduled(fixedDelayString = "${tessary.alert.heartbeat-ms:60000}")
    public void tick() {
        // Bind this tick's span into MDC first so every log this tick emits pivots to its trace.
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            Instant now = Instant.now();
            assembleRollups(now);
            notifyOpenedCases(now);
        }
    }

    /**
     * The anchor advances only on delivery, to the last case delivered, so quiet hours and cadence defer rather than
     * drop and the per-tick cap resumes where it stopped.
     */
    private void notifyOpenedCases(Instant now) {
        List<AlertRuleRow> enabled;
        try {
            enabled = rules.listEnabled(AlertRuleRow.RuleType.CASE_OPENED);
        } catch (RuntimeException e) {
            // ZERO case notifications go out this tick; nothing retries its way out of that.
            log.error(Markers.OPS, "alert case-opened scan failed", e);
            return;
        }
        for (AlertRuleRow rule : enabled) {
            if (!entitledForAlerts(rule.projectId())) continue; // org lacks the alerts capability
            try (LogContext ignored = LogContext.with(LogContext.PROJECT_ID, rule.projectId())) {
                notifyOne(rule, now);
            } catch (RuntimeException e) {
                log.warn(Markers.OPS, "alert case notification failed project={}", rule.projectId(), e);
            }
        }
    }

    private void notifyOne(AlertRuleRow rule, Instant now) {
        AlertPolicy policy = AlertPolicy.of(mapper, rule.attributes());
        Instant anchor = parseAnchor(rule.lastEvaluatedAt(), rule.createdAt());
        if (policy.isQuiet(now) || !policy.cadenceElapsed(anchor, now)) return;
        // Snooze is the manual form of the same hold, and behaves the same way: nothing is delivered and
        // the anchor stays put, so un-snoozing catches up rather than starting from a gap.
        if (AlertEvaluator.isSnoozed(rule.snoozedUntil(), now)) return;

        List<AlertEventRow> firings =
                caseAlerts.due(rule, anchor, now, classifiers.unavailableDetectorKinds(rule.projectId()));
        if (firings.isEmpty()) return;

        String delivered = null;
        for (AlertEventRow firing : firings) {
            persistAndPublish(firing);
            // Advanced even when the insert was a no-op: a duplicate means another backend already
            // delivered this case, and leaving the anchor behind it would make every tick re-read the
            // same rows forever.
            delivered = firing.windowStart();
        }
        if (delivered != null) {
            rules.markEvaluated(rule.id(), delivered);
            log.info(Markers.OPS, "alert case-opened fired count={}", firings.size());
        }
    }

    private void assembleRollups(Instant now) {
        List<AlertRuleRow> enabled;
        try {
            enabled = rules.listEnabledRollups();
        } catch (RuntimeException e) {
            // ZERO roll-ups evaluate this tick.
            log.error(Markers.OPS, "alert roll-up scan failed", e);
            return;
        }
        for (AlertRuleRow rule : enabled) {
            if (!entitledForAlerts(rule.projectId())) continue; // org lacks the alerts capability
            try (LogContext ignored = LogContext.with(LogContext.PROJECT_ID, rule.projectId())) {
                maybeRollup(rule, now);
            } catch (RuntimeException e) {
                log.warn(Markers.OPS, "alert roll-up failed project={} rule={}", rule.projectId(), rule.id(), e);
            }
        }
    }

    /**
     * The window is {@code [anchor, now)}. A snoozed or empty period still advances the anchor; a throw does not, so
     * the period is retried next heartbeat.
     */
    private void maybeRollup(AlertRuleRow rule, Instant now) {
        boolean digest = AlertRuleRow.RuleType.DIGEST.equals(rule.ruleType());
        String cron = digest ? resolveDigestCron(rule.digestCron()) : rule.briefCron();
        if (cron == null || cron.isBlank()) return; // not schedulable
        String lastAt = digest ? rule.lastDigestAt() : rule.lastBriefAt();
        Instant anchor = parseAnchor(lastAt, rule.createdAt());
        if (!isDue(cron, anchor, now, rule.projectId())) return;
        if (!AlertEvaluator.isSnoozed(rule.snoozedUntil(), now)) {
            Optional<AlertEventRow> row = assembler.assemble(rule, anchor, now);
            row.ifPresent(this::persistAndPublish);
        }
        rules.markRollupRan(rule.id(), rule.ruleType(), now.toString());
    }

    /** Returns true when this call wrote and published the firing. */
    private boolean persistAndPublish(AlertEventRow row) {
        boolean inserted = events.insertIfAbsent(row);
        if (inserted) {
            // Publish only on a fresh insert so a window re-evaluated across backends fires once.
            publisher.publishEvent(new AlertFiredEvent(row));
        }
        return inserted;
    }

    private @Nullable String resolveDigestCron(@Nullable String cron) {
        return (cron != null && !cron.isBlank()) ? cron : props.getDefaultDigestCron();
    }

    private Instant parseAnchor(@Nullable String lastAt, String createdAt) {
        String anchor = (lastAt != null && !lastAt.isBlank()) ? lastAt : createdAt;
        return Instant.parse(anchor);
    }

    private boolean isDue(String cron, Instant anchor, Instant now, String projectId) {
        try {
            ZoneId zone = ZoneId.of(props.getCronZone());
            ZonedDateTime next = CronExpression.parse(cron).next(anchor.atZone(zone));
            return next != null && !now.isBefore(next.toInstant());
        } catch (RuntimeException e) {
            log.warn(Markers.OPS, "alert skipping project={} — bad cron ({}): {}", projectId, cron, e.getMessage());
            return false;
        }
    }
}
