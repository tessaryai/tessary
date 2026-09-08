// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.alert;

import ai.tessary.evals.alert.AlertDtos.UpsertAlertRuleRequest;
import ai.tessary.evals.classifier.ClassifierRepository;
import ai.tessary.evals.classifier.ClassifierRow;
import ai.tessary.evals.open.errors.AlertError;
import ai.tessary.evals.open.errors.ClassifierError;
import ai.tessary.evals.open.errors.EvalsException;
import ai.tessary.evals.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Façade for the alert feature: the unified {@code alert_rule} lifecycle (upsert / snooze /
 * enable-disable / delete) across both grains — per-classifier threshold rules and per-project digest/brief
 * roll-up schedules — plus reading fired alerts. The async evaluation itself lives in {@link AlertWorker};
 * this service owns the config + read side. Read-only on the classifier store — it reads {@code classifier} to
 * validate a threshold rule's target but never mutates it.
 */
@Service
public class AlertService {

    private static final int DEFAULT_WINDOW_SECONDS = 86_400;

    /** What the seeded rule calls itself, in the partner's words rather than the schema's. */
    static final String DEFAULT_CASE_RULE_NAME = "A case opens";

    private final AlertRuleRepository rules;
    private final AlertEventRepository events;
    private final ClassifierRepository classifiers;
    private final ObjectMapper mapper;

    public AlertService(
            AlertRuleRepository rules,
            AlertEventRepository events,
            ClassifierRepository classifiers,
            ObjectMapper mapper) {
        this.rules = rules;
        this.events = events;
        this.classifiers = classifiers;
        this.mapper = mapper;
    }

    // ---- rule lifecycle ---------------------------------------------------------------------------

    public List<AlertRuleRow> listRules(String projectId) {
        return rules.listByProject(projectId);
    }

    public AlertRuleRow getRule(String projectId, String id) {
        return rules.findById(projectId, id).orElseThrow(() -> new EvalsException(AlertError.RULE_NOT_FOUND, id));
    }

    /**
     * Create or replace an alert rule. The natural key is the classifier (threshold) or the {@code (project,
     * rule_type)} pair (digest/brief), so re-upserting reconfigures in place and preserves {@code created_at}
     * + the lifecycle anchors ({@code last_*_at}, {@code snoozed_until}, {@code enabled}). Only the
     * implemented rule types (threshold/digest/brief) are accepted; the reserved ones are rejected.
     */
    public AlertRuleRow upsertRule(String projectId, UpsertAlertRuleRequest req) {
        String ruleType = req.ruleType();
        if (!AlertRuleRow.RuleType.isImplemented(ruleType)) {
            throw new EvalsException(AlertError.UNSUPPORTED_RULE_TYPE, ruleType);
        }
        if (AlertRuleRow.RuleType.THRESHOLD.equals(ruleType)) return upsertThreshold(projectId, req);
        if (AlertRuleRow.RuleType.CASE_OPENED.equals(ruleType)) return upsertCaseOpened(projectId, req);
        return upsertRollup(projectId, req);
    }

    /**
     * Create or reconfigure the project's one case-opened rule (launch requirements I1–I3).
     *
     * <p>Its config is a {@link AlertPolicy} in {@code attributes} — cadence and quiet hours — rather than
     * a cron and a threshold, which are the wrong shape for it: a case opens when it opens, and what a
     * partner controls is not <em>when we look</em> but <em>when they are willing to hear</em>.
     *
     * <p><b>The anchor is preserved on reconfigure, exactly as the roll-ups preserve theirs.</b> Resetting
     * {@code last_evaluated_at} when someone edits their quiet hours would re-fire every case opened since
     * the rule was created — the one bug that would teach a partner to turn the whole thing off.
     */
    private AlertRuleRow upsertCaseOpened(String projectId, UpsertAlertRuleRequest req) {
        AlertRuleRow existing =
                rules.findRollup(projectId, AlertRuleRow.RuleType.CASE_OPENED).orElse(null);
        String now = Instant.now().toString();
        AlertRuleRow row = new AlertRuleRow(
                existing == null ? Ids.ulid() : existing.id(),
                projectId,
                AlertRuleRow.RuleType.CASE_OPENED,
                req.name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                req.severity(),
                null,
                null,
                existing == null || existing.enabled(),
                existing == null ? null : existing.snoozedUntil(),
                existing == null ? now : existing.lastEvaluatedAt(),
                null,
                null,
                req.policyOrDefault().toJson(mapper),
                existing == null ? now : existing.createdAt(),
                now);
        if (existing == null) {
            rules.insert(row);
        } else {
            rules.update(row);
        }
        return rules.findRollup(projectId, AlertRuleRow.RuleType.CASE_OPENED).orElseThrow();
    }

    /**
     * Ensure the project has a case-opened rule, without touching one it already has.
     *
     * <p>Called on project creation so alerting is ON for a partner by default (launch requirement I4).
     * That default is only half a promise on its own — nothing is delivered until a channel exists — but
     * it is the half that matters: the moment they paste a Slack webhook or install the app, cases reach
     * them, with no second setting to find.
     *
     * <p>Idempotent and non-destructive: a project that already has the rule keeps its policy, its
     * enabled flag and its anchor, so the project heartbeat can call this as freely as classifier
     * re-seeding does.
     */
    public boolean ensureCaseOpenedRule(String projectId) {
        if (rules.findRollup(projectId, AlertRuleRow.RuleType.CASE_OPENED).isPresent()) return false;
        String now = Instant.now().toString();
        rules.insert(new AlertRuleRow(
                Ids.ulid(),
                projectId,
                AlertRuleRow.RuleType.CASE_OPENED,
                DEFAULT_CASE_RULE_NAME,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                now, // anchored at creation: a new rule never pages about the backlog that predates it
                null,
                null,
                AlertPolicy.IMMEDIATE.toJson(mapper),
                now,
                now));
        return true;
    }

    private AlertRuleRow upsertThreshold(String projectId, UpsertAlertRuleRequest req) {
        String classifierId = req.classifierId();
        if (classifierId == null || classifierId.isBlank()) {
            throw new EvalsException(AlertError.MISSING_CLASSIFIER, "threshold rule requires a classifier_id");
        }
        requireSignal(projectId, classifierId);
        String basis = req.basis();
        if (basis == null || !AlertRuleRow.Basis.isValid(basis)) {
            throw new EvalsException(AlertError.INVALID_BASIS, String.valueOf(basis));
        }
        AlertRuleRow existing =
                rules.findThresholdBySignal(projectId, classifierId).orElse(null);
        String now = Instant.now().toString();
        AlertRuleRow row = new AlertRuleRow(
                existing == null ? Ids.ulid() : existing.id(),
                projectId,
                AlertRuleRow.RuleType.THRESHOLD,
                req.name(),
                classifierId,
                req.target(),
                basis,
                Math.max(1, req.thresholdOrDefault()),
                Math.max(1, req.windowSecondsOrDefault()),
                req.groupBy(),
                req.minSamples(),
                req.severity(),
                null,
                null,
                existing == null || existing.enabled(),
                existing == null ? null : existing.snoozedUntil(),
                existing == null ? null : existing.lastEvaluatedAt(),
                null,
                null,
                existing == null ? "{}" : existing.attributes(),
                existing == null ? now : existing.createdAt(),
                now);
        if (existing == null) {
            rules.insert(row);
        } else {
            rules.update(row);
        }
        return rules.findThresholdBySignal(projectId, classifierId).orElseThrow();
    }

    private AlertRuleRow upsertRollup(String projectId, UpsertAlertRuleRequest req) {
        String ruleType = req.ruleType();
        boolean brief = AlertRuleRow.RuleType.BRIEF.equals(ruleType);
        String cron = brief ? req.briefCron() : req.digestCron();
        if (brief && (cron == null || cron.isBlank())) {
            throw new EvalsException(AlertError.MISSING_CRON, "brief rule requires a brief_cron");
        }
        AlertRuleRow existing = rules.findRollup(projectId, ruleType).orElse(null);
        String now = Instant.now().toString();
        AlertRuleRow row = new AlertRuleRow(
                existing == null ? Ids.ulid() : existing.id(),
                projectId,
                ruleType,
                req.name(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                req.severity(),
                brief ? null : req.digestCron(),
                brief ? req.briefCron() : null,
                existing == null || existing.enabled(),
                existing == null ? null : existing.snoozedUntil(),
                null,
                existing == null ? null : existing.lastDigestAt(),
                existing == null ? null : existing.lastBriefAt(),
                existing == null ? "{}" : existing.attributes(),
                existing == null ? now : existing.createdAt(),
                now);
        if (existing == null) {
            rules.insert(row);
        } else {
            rules.update(row);
        }
        return rules.findRollup(projectId, ruleType).orElseThrow();
    }

    /** Hard enable/disable — a disabled rule is never evaluated (snooze/disable without touching the classifier). */
    public AlertRuleRow setRuleEnabled(String projectId, String id, boolean enabled) {
        if (rules.setEnabled(projectId, id, enabled) == 0) {
            throw new EvalsException(AlertError.RULE_NOT_FOUND, id);
        }
        return getRule(projectId, id);
    }

    /** Soft-mute a rule until an ISO instant (null clears). The classifier keeps firing detections meanwhile. */
    public AlertRuleRow snoozeRule(String projectId, String id, @Nullable String snoozedUntil) {
        if (rules.snooze(projectId, id, snoozedUntil) == 0) {
            throw new EvalsException(AlertError.RULE_NOT_FOUND, id);
        }
        return getRule(projectId, id);
    }

    public boolean deleteRule(String projectId, String id) {
        return rules.delete(projectId, id);
    }

    /** The threshold rule for a classifier, if configured. */
    public Optional<AlertRuleRow> findThresholdForSignal(String projectId, String classifierId) {
        requireSignal(projectId, classifierId);
        return rules.findThresholdBySignal(projectId, classifierId);
    }

    // ---- fired alerts -----------------------------------------------------------------------------

    public List<AlertEventRow> firedByProject(String projectId, int limit) {
        return events.listByProject(projectId, limit);
    }

    public List<AlertEventRow> firedByRule(String projectId, String ruleId, int limit) {
        return events.listByRule(projectId, ruleId, limit);
    }

    public List<AlertEventRow> firedByClassifier(String projectId, String classifierId, int limit) {
        requireSignal(projectId, classifierId);
        return events.listByClassifier(projectId, classifierId, limit);
    }

    private ClassifierRow requireSignal(String projectId, String classifierId) {
        return classifiers
                .findById(projectId, classifierId)
                .orElseThrow(() -> new EvalsException(ClassifierError.NOT_FOUND, classifierId));
    }
}
