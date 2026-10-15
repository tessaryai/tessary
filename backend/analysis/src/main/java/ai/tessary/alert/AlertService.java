// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import ai.tessary.alert.AlertDtos.UpsertAlertRuleRequest;
import ai.tessary.classifier.ClassifierRepository;
import ai.tessary.classifier.ClassifierRow;
import ai.tessary.open.errors.AlertError;
import ai.tessary.open.errors.ClassifierError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.Ids;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The {@code alert_rule} lifecycle and fired-alert reads; evaluation lives in {@link AlertWorker}. Never mutates
 * {@code classifier}.
 */
@Service
public class AlertService {

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

    public List<AlertRuleRow> listRules(String projectId) {
        return rules.listByProject(projectId);
    }

    public AlertRuleRow getRule(String projectId, String id) {
        return rules.findById(projectId, id).orElseThrow(() -> new TessaryException(AlertError.RULE_NOT_FOUND, id));
    }

    /**
     * Re-upserting reconfigures in place and preserves {@code created_at} and the lifecycle anchors. Only implemented
     * rule types are accepted.
     */
    public AlertRuleRow upsertRule(String projectId, UpsertAlertRuleRequest req) {
        String ruleType = req.ruleType();
        if (!AlertRuleRow.RuleType.isImplemented(ruleType)) {
            throw new TessaryException(AlertError.UNSUPPORTED_RULE_TYPE, ruleType);
        }
        if (AlertRuleRow.RuleType.THRESHOLD.equals(ruleType)) return upsertThreshold(projectId, req);
        if (AlertRuleRow.RuleType.CASE_OPENED.equals(ruleType)) return upsertCaseOpened(projectId, req);
        return upsertRollup(projectId, req);
    }

    /**
     * Configured by an {@link AlertPolicy} (cadence, quiet hours) rather than a cron. The anchor is preserved on
     * reconfigure: resetting it would re-fire every case since the rule was created.
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
     * Called on project creation so alerting is on by default. Idempotent and non-destructive, so the heartbeat can
     * call it freely.
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
            throw new TessaryException(AlertError.MISSING_CLASSIFIER, "threshold rule requires a classifier_id");
        }
        requireSignal(projectId, classifierId);
        String basis = req.basis();
        if (basis == null || !AlertRuleRow.Basis.isValid(basis)) {
            throw new TessaryException(AlertError.INVALID_BASIS, String.valueOf(basis));
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
            throw new TessaryException(AlertError.MISSING_CRON, "brief rule requires a brief_cron");
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

    public AlertRuleRow setRuleEnabled(String projectId, String id, boolean enabled) {
        if (rules.setEnabled(projectId, id, enabled) == 0) {
            throw new TessaryException(AlertError.RULE_NOT_FOUND, id);
        }
        return getRule(projectId, id);
    }

    /** The classifier keeps firing detections meanwhile. */
    public AlertRuleRow snoozeRule(String projectId, String id, @Nullable String snoozedUntil) {
        if (rules.snooze(projectId, id, snoozedUntil) == 0) {
            throw new TessaryException(AlertError.RULE_NOT_FOUND, id);
        }
        return getRule(projectId, id);
    }

    public boolean deleteRule(String projectId, String id) {
        return rules.delete(projectId, id);
    }

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
                .orElseThrow(() -> new TessaryException(ClassifierError.NOT_FOUND, classifierId));
    }
}
