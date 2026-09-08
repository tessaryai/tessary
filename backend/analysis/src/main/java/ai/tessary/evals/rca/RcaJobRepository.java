// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.rca;

import ai.tessary.evals.open.jobqueue.JobRow;
import ai.tessary.evals.open.jobqueue.LeasedJobSql;
import ai.tessary.evals.tenant.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The leased queue for RCA jobs on the unified {@code job} table — mirrors {@link
 * ai.tessary.evals.classifier.grader.GraderRunJobRepository}'s finite-job grain (no cursor, no dead-letter
 * cooldown). {@link #createOrGet} is the race-safe coalesce: a concurrent trigger for the same
 * finding resolves to the same job id either way (see {@link RcaJobRow} for the dedupe grain).
 */
@Repository
public class RcaJobRepository {

    private static final String COLS = "id, project_id, payload->>'finding_id' AS finding_id, "
            + "payload->>'subject_kind' AS subject_kind, "
            + "payload->>'subject_id' AS subject_id, payload->>'metric' AS metric, "
            + "payload->>'window_from' AS window_from, payload->>'window_split' AS window_split, "
            + "payload->>'window_to' AS window_to, payload->>'created_by' AS created_by, "
            + "status, lease_owner, lease_expires_at, attempts, last_error, created_at, updated_at";

    private final JdbcClient jdbc;

    public RcaJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Enqueue the RCA for one finding, or resolve to the already-enqueued job for it — one statement,
     * race-safe via the {@code ux_job_rca} no-op-update-with-{@code RETURNING} upsert (the same shape as
     * {@code GraderRunJobRepository#enqueue}). The dedupe key is built HERE, and starts with the project
     * id — {@code ux_job_rca} is a global index, so two projects must never coalesce onto one job. Returns the job id either way. The
     * one deliberate difference: re-triggering a {@code failed} event revives it to {@code pending}
     * with a fresh attempt budget — {@code markFailed} is terminal on this queue (the claim SQL never
     * reclaims {@code failed}), so without the revival a transient LLM blip would lock the whole
     * finding behind its dedupe key. A {@code done} report stays immutable.
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
            @Nullable String createdBy) {
        return createOrGet(
                projectId,
                findingId,
                subjectKind,
                subjectId,
                metric,
                windowFrom,
                windowSplit,
                windowTo,
                createdBy,
                null);
    }

    /**
     * As {@link #createOrGet}, with an optional {@code runNonce} appended to the dedupe key. A null
     * nonce is the normal coalescing trigger. A non-null one deliberately opts OUT of the coalesce —
     * it is how an explicit re-run gets a second analysis of a finding whose report already exists,
     * instead of silently resolving back to the first (immutable) report.
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
            @Nullable String createdBy,
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

    public Optional<RcaJobRow> findById(String projectId, String id) {
        return jdbc.sql("SELECT " + COLS + " FROM job WHERE kind = :kind AND project_id = :pid AND id = :id")
                .param("kind", JobRow.Kind.RCA)
                .param("pid", projectId)
                .param("id", id)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /** Claim up to {@code batch} due jobs via {@code FOR UPDATE SKIP LOCKED}, reclaiming expired-lease jobs
     *  under the attempt cap. Oldest-first by {@code updated_at}. */
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

    /** Fail jobs whose lease expired with attempts at/over the cap (hung/crashed mid-analysis). */
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
                rs.getString("window_from"),
                rs.getString("window_split"),
                rs.getString("window_to"),
                rs.getString("created_by"),
                rs.getString("status"),
                rs.getString("lease_owner"),
                rs.getString("lease_expires_at"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
