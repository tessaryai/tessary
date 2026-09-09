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
 * The async alerting worker. On an operational heartbeat it (1) scans enabled per-project roll-up rules
 * (digest/brief) and, for each whose cron is now due, assembles a roll-up via {@link AlertAssembler},
 * persists it idempotently, publishes the event, and advances that rule's anchor; and (2) notifies on
 * cases that opened since its last pass.
 *
 * <p><b>Threshold rules are no longer evaluated here.</b> Counting a classifier's detections over a
 * window and paging someone was the alerting subsystem reaching into another slice's data to make a
 * judgement about it. That judgement moved to the classifier itself — {@code ClassifierArming} files a
 * finding, a case opens from it, and {@code case_opened} carries it to the same channels — so alerting
 * is back to delivering what other slices decided. Migration {@code 0089} translated every enabled
 * threshold rule's numbers onto its classifier and disabled the rules.
 *
 * <p>Strictly off the ingest hot path: it reads cases and roll-ups (read-only) and the
 * {@code alert_rule} table.
 * Alerts is a paid capability: the worker runs unconditionally and skips non-entitled orgs per-project
 * ({@code Capability.ALERTS}). The idempotent {@code (alert_rule_id, window_start)} insert is the
 * multi-instance double-fire guard; the {@code AlertFiredEvent} is published ONLY when the insert wrote a
 * new row.
 */
@Component
public class AlertWorker {

    private static final Logger log = LoggerFactory.getLogger(AlertWorker.class);

    private final AlertRuleRepository rules;
    private final AlertEventRepository events;
    private final AlertAssembler assembler;
    private final CaseAlertEvaluator caseAlerts;
    private final ObjectMapper mapper;
    /** The capability gate for the classifier a rule points at — see {@link #classifierStillReaches}. */
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

    /**
     * Alerts are a paid capability ({@link Feature#ALERTS}). Resolve the project's org and consult
     * {@link CapabilityService} so no alert is evaluated or delivered for a free org. Unknown project →
     * not entitled (fail closed).
     */
    private boolean entitledForAlerts(String projectId) {
        return projects.findById(projectId)
                .map(p -> capabilities.isEnabled(p.orgId(), Capability.ALERTS))
                .orElse(false);
    }

    /**
     * Whether a rule still has a classifier behind it that this org has.
     *
     * <p>A rule counts one classifier's detections over a rolling window, so a classifier whose capability
     * went off would go quiet on its own eventually — as the window rolls past its last detection. That is
     * not good enough: the window can be days long, and until it empties the rule can still breach and page
     * someone about a detector their organization no longer has. It is also the one flag-off consequence
     * that reaches a human at 3am rather than a screen they chose to open.
     *
     * <p>Rules with no {@code classifier_id} (roll-ups, and thresholds on something other than a classifier)
     * are unaffected — there is no classifier to withhold.
     */
    private boolean classifierStillReaches(AlertRuleRow rule) {
        String classifierId = rule.classifierId();
        return classifierId == null || classifiers.reachesProject(rule.projectId(), classifierId);
    }

    @Scheduled(fixedDelayString = "${tessary.alert.heartbeat-ms:60000}")
    public void tick() {
        // Bind this tick's Micrometer/OTel span (if any) into MDC before doing any work, so every
        // log this tick emits — including per-rule LogContext scopes below — pivots to its trace.
        try (LogContext ignored = traceBridge.bindCurrentTrace()) {
            Instant now = Instant.now();
            assembleRollups(now);
            notifyOpenedCases(now);
        }
    }

    /**
     * Fire about the cases that opened since each case-opened rule last delivered (launch requirement I2).
     *
     * <p><b>The anchor is the whole mechanism, and it advances only on delivery.</b> A tick that is
     * inside the rule's quiet hours, or too soon under its cadence, returns without touching
     * {@code last_evaluated_at} — so everything that opened while it was held is still "since the anchor"
     * when the window reopens and goes out then. Quiet hours therefore DEFER rather than drop, which is
     * the difference between a partner who is not woken at 3am and a partner who is never told
     * ({@link AlertPolicy}).
     *
     * <p>It advances to the last case actually delivered rather than to {@code now}, so the per-tick cap
     * is a pacing device and not a data loss: the next tick resumes exactly where this one stopped.
     */
    private void notifyOpenedCases(Instant now) {
        List<AlertRuleRow> enabled;
        try {
            enabled = rules.listEnabled(AlertRuleRow.RuleType.CASE_OPENED);
        } catch (RuntimeException e) {
            // Same reasoning as the two scans above: ZERO case notifications go out this tick, and that is
            // a degradation of the pipeline rather than a condition anything retries its way out of.
            log.error(Markers.OPS, "alert case-opened scan failed", e);
            return;
        }
        for (AlertRuleRow rule : enabled) {
            if (!entitledForAlerts(rule.projectId())) continue; // paid capability — skip non-entitled orgs
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

    /** For each enabled roll-up rule, fire it if its cron is due (anchored on the last-ran instant). */
    private void assembleRollups(Instant now) {
        List<AlertRuleRow> enabled;
        try {
            enabled = rules.listEnabledRollups();
        } catch (RuntimeException e) {
            // Same reasoning as the threshold claim above: ZERO roll-ups evaluate this tick.
            log.error(Markers.OPS, "alert roll-up scan failed", e);
            return;
        }
        for (AlertRuleRow rule : enabled) {
            if (!entitledForAlerts(rule.projectId())) continue; // paid capability — skip non-entitled orgs
            if (!classifierStillReaches(rule)) continue; // its classifier is flagged off for this org
            try (LogContext ignored = LogContext.with(LogContext.PROJECT_ID, rule.projectId())) {
                maybeRollup(rule, now);
            } catch (RuntimeException e) {
                log.warn(Markers.OPS, "alert roll-up failed project={} rule={}", rule.projectId(), rule.id(), e);
            }
        }
    }

    /**
     * Fire one roll-up rule if its cron is due. The window is {@code [anchor, now)} — the period since the
     * last roll-up — so the digest/brief covers exactly the activity accrued since it last ran. A null cron
     * means the rule is not schedulable (skipped). A snoozed or empty period still advances the anchor (the
     * cadence stays on schedule), but a *throw* during assembly/persist does NOT — the anchor advances only
     * on success, so a transient failure leaves the period due to be retried next heartbeat.
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
        // Reached only when the period was assembled+persisted (or deliberately suppressed by snooze) without
        // throwing; a throw above propagates and leaves the anchor where it was so the period is retried.
        rules.markRollupRan(rule.id(), rule.ruleType(), now.toString());
    }

    /** Idempotent write + event publish. Returns true when this call actually wrote (and published) the firing. */
    private boolean persistAndPublish(AlertEventRow row) {
        boolean inserted = events.insertIfAbsent(row);
        if (inserted) {
            // Publish ONLY on a fresh insert so a window re-evaluated across N backends fires once. The
            // listener registers AFTER_COMMIT; the synchronous publish here is fine inline (no
            // surrounding tx) — fallbackExecution on the listener handles the no-transaction case.
            publisher.publishEvent(new AlertFiredEvent(row));
        }
        return inserted;
    }

    private @Nullable String resolveDigestCron(@Nullable String cron) {
        return (cron != null && !cron.isBlank()) ? cron : props.getDefaultDigestCron();
    }

    private Instant parseAnchor(@Nullable String lastAt, String createdAt) {
        String anchor = (lastAt != null && !lastAt.isBlank()) ? lastAt : createdAt;
        try {
            return Instant.parse(anchor);
        } catch (RuntimeException e) {
            return Instant.EPOCH; // unparseable anchor → treat as long overdue (fire once, then it self-corrects)
        }
    }

    private boolean isDue(String cron, Instant anchor, Instant now, String projectId) {
        try {
            ZoneId zone = ZoneId.of(props.getCronZone());
            ZonedDateTime next = CronExpression.parse(cron).next(anchor.atZone(zone));
            return next != null && !now.isBefore(next.toInstant());
        } catch (RuntimeException e) {
            log.warn(
                    Markers.OPS,
                    "alert skipping project={} — bad cron/anchor ({}): {}",
                    projectId,
                    cron,
                    e.getMessage());
            return false;
        }
    }
}
