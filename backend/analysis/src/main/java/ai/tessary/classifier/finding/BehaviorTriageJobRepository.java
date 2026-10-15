// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.config.ClassifierProperties;
import ai.tessary.open.jobqueue.LeasedJobSql;
import ai.tessary.tenant.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A separate kind from {@code grader_run}: this asks whether the detector's claim is true, which a passing grader says
 * nothing about. The dedupe key covers every status, so a finding gets exactly one triage run; a recurrence after a
 * ruling opens a fresh finding.
 */
@Repository
public class BehaviorTriageJobRepository {

    static final String KIND = "triage";

    private static final String COLS = "id, project_id, payload->>'finding_id' AS finding_id, "
            + "status, lease_owner, lease_expires_at, attempts, last_error, created_at, updated_at";

    private static final String REVIVABLE = LeasedJobSql.deadLetterCooldownGate("job", BehaviorTriageJobRow.DEAD);

    /**
     * {@code CASE} rather than a WHERE: Postgres returns no row from
     * {@code ON CONFLICT DO UPDATE ... WHERE false RETURNING}, and {@link #enqueue} needs the row back. Lease columns
     * are left alone; {@code claimBatch} overwrites them.
     */
    private static final String REVIVE_IF_COOLED =
            "status = CASE WHEN " + REVIVABLE + " THEN 'pending' ELSE job.status END,"
                    + " attempts = CASE WHEN " + REVIVABLE + " THEN 0 ELSE job.attempts END,"
                    + " last_error = CASE WHEN " + REVIVABLE + " THEN NULL ELSE job.last_error END,"
                    + " updated_at = CASE WHEN " + REVIVABLE + " THEN :now ELSE job.updated_at END";

    private final JdbcClient jdbc;
    private final ClassifierProperties props;

    public BehaviorTriageJobRepository(JdbcClient jdbc, ClassifierProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    private String deadFloor() {
        return Instant.now()
                .minus(Duration.ofSeconds(props.deadLetterCooldownSeconds()))
                .toString();
    }

    public record EnqueueOutcome(String jobId, String jobStatus) {}

    /**
     * The dedupe key starts with the project id because {@code ux_job_triage} is global. Unlike RCA it does not revive
     * on every touch, so a failed finding does not re-spend a microVM each sweep; only a dead job past its cooldown
     * goes back to pending.
     */
    public EnqueueOutcome enqueue(String projectId, String findingId, String now) {
        return jdbc.sql("""
                INSERT INTO job (id, project_id, kind, status, attempts, dedupe_key, payload, created_at, updated_at)
                VALUES (:id, :pid, '""" + KIND + """
                ', 'pending', 0, :dedupeKey, jsonb_build_object('finding_id', :findingId::text), :now, :now)
                ON CONFLICT (dedupe_key) WHERE kind = '""" + KIND + """
                ' DO UPDATE SET\s""" + REVIVE_IF_COOLED + """

                RETURNING id, status
                """)
                .param("id", Ids.ulid())
                .param("pid", projectId)
                .param("dedupeKey", dedupeKey(projectId, findingId))
                .param("findingId", findingId)
                .param("deadFloor", deadFloor())
                .param("now", now)
                .query((rs, n) -> new EnqueueOutcome(rs.getString("id"), rs.getString("status")))
                .single();
    }

    public List<BehaviorTriageJobRow> claimBatch(String leaseOwner, int batch, long leaseSeconds, int maxAttempts) {
        return jdbc.sql(LeasedJobSql.claimBatchOfKind("job", COLS, "updated_at"))
                .param("kind", KIND)
                .param("owner", leaseOwner)
                .param(
                        "expires",
                        Instant.now().plus(Duration.ofSeconds(leaseSeconds)).toString())
                .param("now", Instant.now().toString())
                .param("batch", batch)
                .param("maxAttempts", maxAttempts)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * Terminal status is {@code dead}, not {@code failed}: a {@code failed} triage was reachable by no path back and
     * stayed un-triaged forever.
     */
    public int failExhausted(int maxAttempts) {
        return jdbc.sql(LeasedJobSql.failExhaustedOfKind(
                        "job", "hung or crashed mid-triage", BehaviorTriageJobRow.DEAD))
                .param("kind", KIND)
                .param("now", Instant.now().toString())
                .param("maxAttempts", maxAttempts)
                .update();
    }

    /**
     * A dead-lettered run and an in-flight one look identical in {@code finding}, so this is read to tell them apart.
     * Only dead rows, or the surface would flap on every retry. One query, avoiding an N+1 on the busiest page.
     */
    public Map<String, FailedTriage> failedByFinding(String projectId, List<String> findingIds) {
        if (findingIds.isEmpty()) return Map.of();
        List<FailedTriage> rows = jdbc.sql("SELECT payload->>'finding_id' AS finding_id, attempts, last_error FROM job"
                        + " WHERE kind = :kind AND project_id = :pid AND status = :dead"
                        + "   AND payload->>'finding_id' IN (:findingIds)"
                        + " ORDER BY attempts ASC, created_at ASC")
                .param("kind", KIND)
                .param("pid", projectId)
                .param("dead", BehaviorTriageJobRow.DEAD)
                .param("findingIds", findingIds)
                .query((rs, n) ->
                        new FailedTriage(rs.getString("finding_id"), rs.getInt("attempts"), rs.getString("last_error")))
                .list();
        Map<String, FailedTriage> out = new LinkedHashMap<>();
        for (FailedTriage row : rows) out.put(row.findingId(), row);
        return out;
    }

    public record FailedTriage(
            String findingId, int attempts, @Nullable String lastError) {}

    public void markDone(String id) {
        jdbc.sql("UPDATE job SET status = 'done', last_error = NULL, updated_at = :now WHERE id = :id")
                .param("now", Instant.now().toString())
                .param("id", id)
                .update();
    }

    /**
     * Not {@code failed}, which would strand the finding un-triaged and invisible; not {@code pending}, whose claim arm
     * ignores {@code attempts} and would retry forever. The delay turns a deterministic failure from a spin into a
     * retry.
     */
    public void markRetryable(String id, @Nullable String error, long delaySeconds) {
        Instant now = Instant.now();
        jdbc.sql("UPDATE job SET lease_expires_at = :expires, last_error = :error, updated_at = :now"
                        + " WHERE id = :id")
                .param("error", error == null ? null : error.substring(0, Math.min(error.length(), 500)))
                .param("expires", now.plusSeconds(Math.max(0, delaySeconds)).toString())
                .param("now", now.toString())
                .param("id", id)
                .update();
    }

    /**
     * For failures that were never about this job: a launcher refusing everything must not dead-letter a queue of good
     * findings. Floored at zero against a double release.
     */
    public void releaseWithoutAttempt(String id, @Nullable String error) {
        releaseWithoutAttempt(id, error, 0L);
    }

    /**
     * For a condition only a human can clear (a missing credential): immediate release would re-claim every tick and
     * fill {@code CLAIM_BATCH} rounds ahead of runnable findings.
     */
    public void releaseWithoutAttempt(String id, @Nullable String error, long delaySeconds) {
        Instant now = Instant.now();
        jdbc.sql("UPDATE job SET lease_expires_at = :expires, attempts = GREATEST(attempts - 1, 0),"
                        + " last_error = :error, updated_at = :now WHERE id = :id")
                .param("error", error == null ? null : error.substring(0, Math.min(error.length(), 500)))
                .param("expires", now.plusSeconds(Math.max(0, delaySeconds)).toString())
                .param("now", now.toString())
                .param("id", id)
                .update();
    }

    private static String dedupeKey(String projectId, String findingId) {
        return projectId + ":" + findingId;
    }

    private static BehaviorTriageJobRow map(ResultSet rs) throws SQLException {
        return new BehaviorTriageJobRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("finding_id"),
                rs.getString("status"),
                rs.getString("lease_owner"),
                rs.getString("lease_expires_at"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
