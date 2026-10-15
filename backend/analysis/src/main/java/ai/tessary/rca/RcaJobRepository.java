// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import ai.tessary.open.jobqueue.JobRow;
import ai.tessary.open.jobqueue.LeasedJobSql;
import ai.tessary.tenant.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The leased RCA queue on {@code job}: finite jobs, no cursor, no dead-letter cooldown. */
@Repository
public class RcaJobRepository {

    private static final String COLS = "id, project_id, payload->>'finding_id' AS finding_id, "
            + "payload->>'subject_kind' AS subject_kind, "
            + "payload->>'subject_id' AS subject_id, payload->>'metric' AS metric, "
            + "payload->>'created_by' AS created_by";

    private final JdbcClient jdbc;

    public RcaJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Race-safe coalesce via the {@code ux_job_rca} upsert. The dedupe key starts with the project id since the index
     * is global. Re-triggering a {@code failed} job revives it (failed is terminal for the claim SQL); {@code done}
     * stays immutable. A non-null {@code runNonce} opts out of coalescing for an explicit re-run.
     */
    public String createOrGet(
            String projectId,
            String findingId,
            String subjectKind,
            String subjectId,
            String metric,
            Instant windowFrom,
            Instant windowSplit,
            Instant windowTo,
            String createdBy,
            @Nullable String runNonce) {
        String dedupeKey = projectId + ":finding:" + findingId + (runNonce == null ? "" : ":" + runNonce);
        String now = Instant.now().toString();
        return jdbc.sql("""
                INSERT INTO job (id, project_id, kind, status, attempts, dedupe_key, payload, created_at, updated_at)
                VALUES (:id, :pid, 'rca', 'pending', 0, :dedupeKey, jsonb_build_object(
                    'finding_id', :findingId::text,
                    'subject_kind', :subjectKind::text, 'subject_id', :subjectId::text, 'metric', :metric::text,
                    'window_from', :windowFrom::text, 'window_split', :windowSplit::text, 'window_to', :windowTo::text,
                    'created_by', :createdBy::text
                ), :now, :now)
                ON CONFLICT (dedupe_key) WHERE kind = 'rca' DO UPDATE SET
                    updated_at = :now,
                    status = CASE WHEN job.status = 'failed' THEN 'pending' ELSE job.status END,
                    attempts = CASE WHEN job.status = 'failed' THEN 0 ELSE job.attempts END,
                    last_error = CASE WHEN job.status = 'failed' THEN NULL ELSE job.last_error END
                RETURNING id
                """)
                .param("id", Ids.ulid())
                .param("pid", projectId)
                .param("dedupeKey", dedupeKey)
                .param("findingId", findingId)
                .param("subjectKind", subjectKind)
                .param("subjectId", subjectId)
                .param("metric", metric)
                .param("windowFrom", windowFrom.toString())
                .param("windowSplit", windowSplit.toString())
                .param("windowTo", windowTo.toString())
                .param("createdBy", createdBy)
                .param("now", now)
                .query(String.class)
                .single();
    }

    /** Oldest-first by {@code updated_at}, reclaiming expired leases under the attempt cap. */
    public List<RcaJobRow> claimBatch(String leaseOwner, int batch, long leaseSeconds, int maxAttempts) {
        String now = Instant.now().toString();
        String expires = Instant.now().plus(Duration.ofSeconds(leaseSeconds)).toString();
        return jdbc.sql(LeasedJobSql.claimBatchOfKind("job", COLS, "updated_at"))
                .param("kind", JobRow.Kind.RCA)
                .param("owner", leaseOwner)
                .param("expires", expires)
                .param("now", now)
                .param("batch", batch)
                .param("maxAttempts", maxAttempts)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** Hung or crashed mid-analysis. */
    public int failExhausted(int maxAttempts) {
        return jdbc.sql(LeasedJobSql.failExhaustedOfKind("job", "hung or crashed mid-rca", RcaJobRow.FAILED))
                .param("kind", JobRow.Kind.RCA)
                .param("now", Instant.now().toString())
                .param("maxAttempts", maxAttempts)
                .update();
    }

    public void markDone(String id) {
        jdbc.sql("UPDATE job SET status = 'done', last_error = NULL, updated_at = :now WHERE id = :id")
                .param("now", Instant.now().toString())
                .param("id", id)
                .update();
    }

    public void markFailed(String id, @Nullable String error, int maxAttempts) {
        jdbc.sql(LeasedJobSql.markFailedById("job", RcaJobRow.FAILED))
                .param("err", error)
                .param("now", Instant.now().toString())
                .param("id", id)
                .param("maxAttempts", maxAttempts)
                .query(String.class)
                .single();
    }

    private static RcaJobRow map(ResultSet rs) throws SQLException {
        return new RcaJobRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("finding_id"),
                rs.getString("subject_kind"),
                rs.getString("subject_id"),
                rs.getString("metric"),
                rs.getString("created_by"));
    }
}
