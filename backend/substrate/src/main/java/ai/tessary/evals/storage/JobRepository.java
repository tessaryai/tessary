// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import ai.tessary.evals.open.jobqueue.JobRow;
import ai.tessary.evals.open.jobqueue.LeasePolicy;
import ai.tessary.evals.open.jobqueue.LeasedJobSql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The shared JdbcClient store over the unified {@code job} table — the single place the leased-queue
 * SKIP-LOCKED machinery ({@link LeasedJobSql}, kind-scoped) is issued for the consolidated queue. The
 * per-feature queues build their {@code LeasedJobQueue<J>} on top of this: they
 * enqueue a {@link JobRow} with their {@code kind} + {@code payload}, claim by kind, and map the raw row
 * back to their domain job.
 */
@Repository
public class JobRepository {

    private static final String COLS = "id, project_id, kind, status, lease_owner, lease_expires_at, attempts, "
            + "last_error, dedupe_key, cursor_at, cursor_id, progress, payload, created_at, updated_at";

    private final JdbcClient jdbc;

    public JobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert one job row (payload/progress bound as jsonb). */
    public void insert(JobRow row) {
        jdbc.sql("""
            INSERT INTO job (id, project_id, kind, status, lease_owner, lease_expires_at, attempts, last_error,
                             dedupe_key, cursor_at, cursor_id, progress, payload, created_at, updated_at)
            VALUES (:id, :pid, :kind, :status, :owner, :expires, :attempts, :err, :dedupe, :cursorAt, :cursorId,
                    CAST(:progress AS JSONB), CAST(:payload AS JSONB), :created, :updated)
            """)
                .param("id", row.id())
                .param("pid", row.projectId())
                .param("kind", row.kind())
                .param("status", row.status())
                .param("owner", row.leaseOwner())
                .param("expires", row.leaseExpiresAt())
                .param("attempts", row.attempts())
                .param("err", row.lastError())
                .param("dedupe", row.dedupeKey())
                .param("cursorAt", row.cursorAt())
                .param("cursorId", row.cursorId())
                .param("progress", row.progress())
                .param("payload", row.payload())
                .param("created", row.createdAt())
                .param("updated", row.updatedAt())
                .update();
    }

    /**
     * Claim up to {@code policy.batch()} due jobs of {@code kind} under a fresh lease (SKIP LOCKED),
     * reclaiming expired-lease jobs still under the attempt cap. Oldest-first by {@code updated_at}.
     */
    public List<JobRow> claimBatch(String kind, String leaseOwner, LeasePolicy policy) {
        Instant now = Instant.now();
        return jdbc.sql(LeasedJobSql.claimBatchOfKind("job", COLS, "updated_at"))
                .param("kind", kind)
                .param("owner", leaseOwner)
                .param("expires", policy.expiresAt(now).toString())
                .param("now", now.toString())
                .param("batch", policy.batch())
                .param("maxAttempts", policy.maxAttempts())
                .query((rs, n) -> map(rs))
                .list();
    }

    /** Dead-letter jobs of {@code kind} whose lease expired at/over the attempt cap. Returns the count. */
    public int failExhausted(String kind, String reason, LeasePolicy policy) {
        return jdbc.sql(LeasedJobSql.failExhaustedOfKind("job", reason))
                .param("kind", kind)
                .param("now", Instant.now().toString())
                .param("maxAttempts", policy.maxAttempts())
                .update();
    }

    /** Mark a claimed job terminal-done. */
    public void markDone(String id) {
        jdbc.sql("UPDATE job SET status = 'done', last_error = NULL, updated_at = :now WHERE id = :id")
                .param("now", Instant.now().toString())
                .param("id", id)
                .update();
    }

    /** Mark a claimed job failed with a short reason. */
    public void markFailed(String id, String error) {
        jdbc.sql("UPDATE job SET status = 'failed', last_error = :err, updated_at = :now WHERE id = :id")
                .param("err", error != null && error.length() > 2000 ? error.substring(0, 2000) : error)
                .param("now", Instant.now().toString())
                .param("id", id)
                .update();
    }

    public Optional<JobRow> findById(String id) {
        return jdbc.sql("SELECT " + COLS + " FROM job WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    private static JobRow map(ResultSet rs) throws SQLException {
        return new JobRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("kind"),
                rs.getString("status"),
                rs.getString("lease_owner"),
                rs.getString("lease_expires_at"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getString("dedupe_key"),
                rs.getString("cursor_at"),
                rs.getString("cursor_id"),
                rs.getString("progress"),
                rs.getString("payload"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
