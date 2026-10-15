// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * CRUD and scans for {@code alert_rule}. No threshold claim: threshold evaluation moved into {@code ClassifierArming},
 * and threshold rows stay disabled as a record (migration {@code 0089}).
 */
@Repository
public class AlertRuleRepository {

    private static final String COLS =
            "id, project_id, rule_type, name, classifier_id, target, basis, threshold, window_seconds, "
                    + "group_by, min_samples, severity, digest_cron, brief_cron, enabled, snoozed_until, "
                    + "last_evaluated_at, last_digest_at, last_brief_at, attributes, created_at, updated_at";

    private final JdbcClient jdbc;

    public AlertRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AlertRuleRow> findById(String projectId, String id) {
        return jdbc.sql("SELECT " + COLS + " FROM alert_rule WHERE project_id = :pid AND id = :id")
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public Optional<AlertRuleRow> findThresholdBySignal(String projectId, String classifierId) {
        return jdbc.sql("SELECT " + COLS + " FROM alert_rule "
                        + "WHERE project_id = :pid AND classifier_id = :sid AND rule_type = 'threshold'")
                .param("pid", projectId)
                .param("sid", classifierId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public Optional<AlertRuleRow> findRollup(String projectId, String ruleType) {
        return jdbc.sql("SELECT " + COLS + " FROM alert_rule " + "WHERE project_id = :pid AND rule_type = :rt")
                .param("pid", projectId)
                .param("rt", ruleType)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public List<AlertRuleRow> listByProject(String projectId) {
        return jdbc.sql("SELECT " + COLS + " FROM alert_rule WHERE project_id = :pid ORDER BY created_at")
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public void insert(AlertRuleRow row) {
        jdbc.sql("""
            INSERT INTO alert_rule (id, project_id, rule_type, name, classifier_id, target, basis, threshold,
                                    window_seconds, group_by, min_samples, severity, digest_cron, brief_cron,
                                    enabled, snoozed_until, last_evaluated_at, last_digest_at, last_brief_at,
                                    attributes, created_at, updated_at)
            VALUES (:id, :pid, :rt, :name, :sid, :target, :basis, :threshold, :win, :groupBy, :minSamples,
                    :severity, :digest, :brief, :enabled, :snooze, :lastEval, :lastDigest, :lastBrief,
                    CAST(:attributes AS JSONB), :created, :updated)
            """)
                .param("id", row.id())
                .param("pid", row.projectId())
                .param("rt", row.ruleType())
                .param("name", row.name())
                .param("sid", row.classifierId())
                .param("target", row.target())
                .param("basis", row.basis())
                .param("threshold", row.threshold())
                .param("win", row.windowSeconds())
                .param("groupBy", row.groupBy())
                .param("minSamples", row.minSamples())
                .param("severity", row.severity())
                .param("digest", row.digestCron())
                .param("brief", row.briefCron())
                .param("enabled", row.enabled())
                .param("snooze", row.snoozedUntil())
                .param("lastEval", row.lastEvaluatedAt())
                .param("lastDigest", row.lastDigestAt())
                .param("lastBrief", row.lastBriefAt())
                .param("attributes", row.attributes())
                .param("created", row.createdAt())
                .param("updated", row.updatedAt())
                .update();
    }

    /**
     * Preserves {@code created_at} and the lifecycle anchors; {@code enabled}/{@code snoozed_until} have their own
     * methods.
     */
    public void update(AlertRuleRow row) {
        jdbc.sql("""
            UPDATE alert_rule
            SET name = :name, target = :target, basis = :basis, threshold = :threshold,
                window_seconds = :win, group_by = :groupBy, min_samples = :minSamples, severity = :severity,
                digest_cron = :digest, brief_cron = :brief, attributes = CAST(:attributes AS JSONB),
                updated_at = :updated
            WHERE project_id = :pid AND id = :id
            """)
                .param("name", row.name())
                .param("target", row.target())
                .param("basis", row.basis())
                .param("threshold", row.threshold())
                .param("win", row.windowSeconds())
                .param("groupBy", row.groupBy())
                .param("minSamples", row.minSamples())
                .param("severity", row.severity())
                .param("digest", row.digestCron())
                .param("brief", row.briefCron())
                .param("attributes", row.attributes())
                .param("updated", row.updatedAt())
                .param("pid", row.projectId())
                .param("id", row.id())
                .update();
    }

    /** Returns rows affected (0 = not found). */
    public int setEnabled(String projectId, String id, boolean enabled) {
        return jdbc.sql("UPDATE alert_rule SET enabled = :en, updated_at = :now WHERE project_id = :pid AND id = :id")
                .param("en", enabled)
                .param("now", Instant.now().toString())
                .param("pid", projectId)
                .param("id", id)
                .update();
    }

    /** Null clears. Returns rows affected (0 = not found). */
    public int snooze(String projectId, String id, @Nullable String snoozedUntil) {
        return jdbc.sql("UPDATE alert_rule SET snoozed_until = :until, updated_at = :now "
                        + "WHERE project_id = :pid AND id = :id")
                .param("until", snoozedUntil)
                .param("now", Instant.now().toString())
                .param("pid", projectId)
                .param("id", id)
                .update();
    }

    public boolean delete(String projectId, String id) {
        return jdbc.sql("DELETE FROM alert_rule WHERE project_id = :pid AND id = :id")
                        .param("pid", projectId)
                        .param("id", id)
                        .update()
                > 0;
    }

    public List<AlertRuleRow> listEnabledRollups() {
        return jdbc.sql("SELECT " + COLS + " FROM alert_rule "
                        + "WHERE enabled = TRUE AND rule_type IN ('digest','brief') ORDER BY project_id")
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * No claim needed: two backends build the same firings, and the idempotent {@code (alert_rule_id, case_id)} insert
     * lets exactly one publish.
     */
    public List<AlertRuleRow> listEnabled(String ruleType) {
        return jdbc.sql("SELECT " + COLS + " FROM alert_rule "
                        + "WHERE enabled = TRUE AND rule_type = :rt ORDER BY project_id")
                .param("rt", ruleType)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * For a case-opened rule, set to the last delivered case's {@code opened_at} rather than the wall clock, so a
     * capped tick resumes correctly.
     */
    public void markEvaluated(String id, String at) {
        jdbc.sql("UPDATE alert_rule SET last_evaluated_at = :at, updated_at = :now WHERE id = :id")
                .param("at", at)
                .param("now", Instant.now().toString())
                .param("id", id)
                .update();
    }

    public void markRollupRan(String id, String ruleType, String at) {
        String column = AlertRuleRow.RuleType.DIGEST.equals(ruleType) ? "last_digest_at" : "last_brief_at";
        jdbc.sql("UPDATE alert_rule SET " + column + " = :at, updated_at = :at WHERE id = :id")
                .param("at", at)
                .param("id", id)
                .update();
    }

    private static AlertRuleRow map(ResultSet rs) throws SQLException {
        return new AlertRuleRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("rule_type"),
                rs.getString("name"),
                rs.getString("classifier_id"),
                rs.getString("target"),
                rs.getString("basis"),
                (Integer) rs.getObject("threshold"),
                (Integer) rs.getObject("window_seconds"),
                rs.getString("group_by"),
                (Integer) rs.getObject("min_samples"),
                rs.getString("severity"),
                rs.getString("digest_cron"),
                rs.getString("brief_cron"),
                rs.getBoolean("enabled"),
                rs.getString("snoozed_until"),
                rs.getString("last_evaluated_at"),
                rs.getString("last_digest_at"),
                rs.getString("last_brief_at"),
                rs.getString("attributes"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
