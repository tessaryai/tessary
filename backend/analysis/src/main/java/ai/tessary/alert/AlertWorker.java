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
 * The async alerting worker. On each heartbeat it scans enabled per-project roll-up rules
 * (digest/brief), assembles a roll-up via {@link AlertAssembler} for any whose cron is due,
 * persists it idempotently, publishes the event, and advances that rule's anchor. It also
 * notifies on cases that opened since its last pass.
 *
 * <p>Strictly off the ingest hot path: it reads cases, roll-ups, and the {@code alert_rule}
 * table, all read-only. The worker runs unconditionally and skips projects whose org lacks the
 * alerts capability ({@code Capability.ALERTS}). The idempotent
 * {@code (alert_rule_id, window_start)} insert guards against double-firing across instances;
 * {@code AlertFiredEvent} publishes only when the insert wrote a new row.
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
     * Resolves the project's org and consults {@link CapabilityService} so no alert is evaluated
     * or delivered for an org without the alerts capability enabled. An unknown project is
     * treated as not entitled.
     */
    private boolean entitledForAlerts(String projectId) {
        return projects.findById(projectId)
                .map(p -> capabilities.isEnabled(p.orgId(), Capability.ALERTS))
                .orElse(false);
    }

    /**
     * Whether a rule still has a classifier behind it that this org has.
     *
     * <p>A rule counts one classifier's detections over a rolling window: if the classifier's
     * capability goes off, the rule keeps breaching, and can page someone about a detector their
     * org no longer has, until the window rolls past the last detection (which can take days).
     * Rules with no {@code classifier_id} are unaffected.
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
     * Fires on cases that opened since each case-opened rule last delivered.
     *
     * <p>The anchor advances only on delivery. A tick inside quiet hours, or too soon under the
     * rule's cadence, leaves {@code last_evaluated_at} untouched, so anything that opened while
     * held is still "since the anchor" and goes out once the window reopens ({@link AlertPolicy}
     * defers rather than drops).
     *
     * <p>It advances to the last case actually delivered, not to {@code now}, so the per-tick cap
     * is a pacing device: the next tick resumes exactly where this one stopped.
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
            if (!entitledForAlerts(rule.projectId())) continue; // org lacks the alerts capability
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
