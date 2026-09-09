// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.classifier.ClassifierService;
import ai.tessary.open.jobqueue.JobRow;
import ai.tessary.open.jobqueue.LeasedJobSql;
import ai.tessary.tenant.Ids;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The leased sweep queue for classifiers on the unified {@code job} table: every
 * classifier job is a {@code job} row with {@code kind='classifier'}, its {@code classifier_id} carried in
 * the {@code payload} jsonb, and its natural key in {@code dedupe_key = classifier_id} (backed by the
 * partial unique {@code ux_job_classifier} — one row per classifier across all statuses). The claim /
 * reclaim / dead-letter machinery is the shared
 * kind-scoped {@link LeasedJobSql}; the {@link ClassifierWorker} + enqueue callers work against
 * the {@link ClassifierJobRow} shape, this repo sourcing {@code classifier_id} from the payload in
 * SQL.
 *
 * <p>Grain difference vs. embedding's finite jobs: a classifier job is a <em>resumable sweep</em> — its
 * {@code cursor_at}/{@code cursor_id} high-water mark (kept first-class) advances after each sweep, and a
 * job is re-marked pending on the next heartbeat to continue from where it left off (it re-pends rather
 * than terminating).
 */
@Repository
public class ClassifierJobRepository {

    // classifier_id is sourced from the payload jsonb; the rest are the shared job columns (cursor_at/cursor_id
    // stay first-class for the keyset sweep).
    private static final String COLS = "id, project_id, payload->>'classifier_id' AS classifier_id, status, cursor_at, "
            + "cursor_id, lease_owner, lease_expires_at, attempts, last_error, created_at, updated_at";

    private final JdbcClient jdbc;

    public ClassifierJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Ensure a pending job exists for this classifier. Idempotent: an existing job below the dead-letter
     * state is flipped back to pending (so a fully-swept classifier re-sweeps newly-ingested spans on the
     * next heartbeat); absent, one is inserted. {@code ux_job_classifier} (one row per {@code
     * dedupe_key = classifier_id}) backstops the race.
     *
     * <p>A {@code dead} job (past the attempt cap — fast-failed via {@link #markFailed}, or hung/crashed
     * until its lease-expiry budget ran out via {@link #failExhausted}) is deliberately
     * <b>not</b> resurrected on every call: {@link ClassifierService#enqueueEnabled} calls this
     * unconditionally on every heartbeat, so reviving it immediately would just re-enter the same
     * fast-fail loop the cap exists to stop. It's revived instead once {@code deadLetterCooldownSeconds}
     * has elapsed since it was dead-lettered — bounding the retry-while-down rate while still recovering
     * automatically (no operator action) once the backend is healthy again.
     */
    public void enqueue(String projectId, String classifierId, long deadLetterCooldownSeconds) {
        if (markPending(projectId, classifierId, deadLetterCooldownSeconds) > 0) return;
        String now = Instant.now().toString();
        try {
            jdbc.sql("""
                INSERT INTO job (id, project_id, kind, status, attempts, dedupe_key, payload, created_at, updated_at)
                VALUES (:id, :pid, 'classifier', 'pending', 0, :sid, jsonb_build_object('classifier_id', :sid), :now, :now)
                """)
                    .param("id", Ids.ulid())
                    .param("pid", projectId)
                    .param("sid", classifierId)
                    .param("now", now)
                    .update();
        } catch (DataIntegrityViolationException race) {
            markPending(projectId, classifierId, deadLetterCooldownSeconds);
        }
    }

    /**
     * Flip an existing non-terminal job back to pending without disturbing its cursor or attempts. A
     * {@code dead} job only qualifies once its {@code updated_at} (the moment it was dead-lettered) is
     * older than {@code deadLetterCooldownSeconds}.
     */
    private int markPending(String projectId, String classifierId, long deadLetterCooldownSeconds) {
        String deadFloor = Instant.now()
                .minus(Duration.ofSeconds(deadLetterCooldownSeconds))
                .toString();
        return jdbc.sql("""
            UPDATE job SET status = 'pending', updated_at = :now
            WHERE kind = 'classifier' AND project_id = :pid AND dedupe_key = :sid
              AND (status NOT IN ('claimed', 'dead') OR """ + LeasedJobSql.deadLetterCooldownGate("job", ClassifierJobRow.DEAD) + ")\n")
                .param("now", Instant.now().toString())
                .param("pid", projectId)
                .param("sid", classifierId)
                .param("deadFloor", deadFloor)
                .update();
    }

    /**
     * Claim up to {@code batch} due jobs via {@code FOR UPDATE SKIP LOCKED} (kind-scoped). Reclaims jobs
     * whose lease expired (a worker died mid-sweep) while attempts are under the cap; a poison job is left
     * for {@link #failExhausted}.
     */
    public List<ClassifierJobRow> claimBatch(String leaseOwner, int batch, long leaseSeconds, int maxAttempts) {
        String now = Instant.now().toString();
        String expires = Instant.now().plus(Duration.ofSeconds(leaseSeconds)).toString();
        return jdbc.sql(LeasedJobSql.claimBatchOfKind("job", COLS, "updated_at"))
                .param("kind", JobRow.Kind.CLASSIFIER)
                .param("owner", leaseOwner)
                .param("expires", expires)
                .param("now", now)
                .param("batch", batch)
                .param("maxAttempts", maxAttempts)
                .query((rs, n) -> map(rs))
                .list();
    }

    /** Every sweep job for a project, one row per classifier that has ever been enqueued — the health read. */
    public List<ClassifierJobRow> listByProject(String projectId) {
        return jdbc.sql("SELECT " + COLS + " FROM job WHERE kind = :kind AND project_id = :pid")
                .param("kind", JobRow.Kind.CLASSIFIER)
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * Dead-letter jobs whose lease expired with attempts at/over the cap (hung/crashed mid-sweep).
     * Terminal status is {@code dead}, not the shared default {@code failed}: like the fast-fail leg in
     * {@link #markFailed}, a sweep that exhausted its budget by repeatedly hanging must not be revived by
     * the very next heartbeat's {@link #enqueue} re-pend (that's a retry loop with no backoff) — it waits
     * out the same dead-letter cooldown floor in {@link #markPending} instead.
     */
    public int failExhausted(int maxAttempts) {
        return jdbc.sql(LeasedJobSql.failExhaustedOfKind("job", "hung or crashed mid-sweep", ClassifierJobRow.DEAD))
                .param("kind", JobRow.Kind.CLASSIFIER)
                .param("now", Instant.now().toString())
                .param("maxAttempts", maxAttempts)
                .update();
    }

    /**
     * Advance the keyset sweep cursor {@code (cursor_at, cursor_id)} and mark the job done (terminal until
     * the next enqueue re-pends it). A {@code null} cursor (empty batch, or a removed/disabled classifier)
     * leaves the high-water mark untouched. Both components advance together so the keyset predicate stays
     * consistent. {@code attempts} resets to 0 on a genuine success so {@link #markFailed}'s cap always
     * measures <em>consecutive</em> failures since the last healthy sweep, not a lifetime total.
     */
    public void markSwept(String id, @Nullable String cursorAt, @Nullable String cursorId) {
        jdbc.sql("""
            UPDATE job SET status = 'done',
                           cursor_at = COALESCE(:cursorAt, cursor_at),
                           cursor_id = COALESCE(:cursorId, cursor_id),
                           attempts = 0,
                           last_error = NULL, updated_at = :now
            WHERE id = :id
            """)
                .param("cursorAt", cursorAt)
                .param("cursorId", cursorId)
                .param("now", Instant.now().toString())
                .param("id", id)
                .update();
    }

    /**
     * Drop the keyset high-water mark back to the beginning and re-pend, so the classifier's next sweep
     * re-reads its project's whole observation history. The one operation {@link #markSwept} cannot
     * express: it advances the cursor through {@code COALESCE(:cursorAt, cursor_at)}, which by
     * construction can only move it forward.
     *
     * <p>Called when a call-site fact a detector gates on arrives or changes ({@link
     * ClassifierService#rewindForCallSiteFact}) — history swept while the fact was missing was scored
     * {@code none()} and is now genuinely scoreable. Safe to re-run: {@code ClassifierWorker}'s verdict
     * write is idempotent on {@code (classifier_key, subject)}, so a re-sweep over already-scored
     * observations writes no duplicate detection and escalates no duplicate grader run.
     *
     * <p>Two statuses are excluded, for opposite reasons. A {@code claimed} job is mid-sweep and would
     * {@code markSwept} its own cursor straight back over the rewind — the caller logs the skip so the
     * lost rewind is visible rather than silent. A {@code dead} job must not be revived here at all:
     * {@link #enqueue} deliberately refuses to resurrect one until {@code deadLetterCooldownSeconds}
     * has elapsed, and flipping it to {@code pending} would walk straight through that floor and
     * re-enter the fast-fail loop the cap exists to stop.
     *
     * @return the number of jobs rewound — 0 when the classifier has no job row yet (which needs no
     *     rewind: its first sweep already starts from a null cursor), or when the job was skipped.
     */
    public int rewindCursor(String projectId, String classifierId) {
        return jdbc.sql("""
            UPDATE job SET cursor_at = NULL, cursor_id = NULL, status = 'pending', updated_at = :now
            WHERE kind = 'classifier' AND project_id = :pid AND dedupe_key = :sid
              AND status NOT IN ('claimed', 'dead')
            """)
                .param("now", Instant.now().toString())
                .param("pid", projectId)
                .param("sid", classifierId)
                .update();
    }

    /**
     * Mark a sweep failure. Below {@code maxAttempts}, the job stays {@code failed} — retryable, resurrected
     * by the next {@link #enqueue} call like any other terminal state. At or over the cap, it moves to the
     * terminal {@code dead} state instead, which {@link #markPending} won't resurrect on the routine
     * heartbeat re-pend (only after its cooldown floor), so a job that keeps fast-failing stops burning a
     * claim/fail cycle every heartbeat.
     *
     * @return {@code true} iff this call is the one that crossed the cap (dead-lettered it) — the caller
     *     logs that transition at ERROR exactly once; a below-cap failure stays WARN.
     */
    public boolean markFailed(String id, @Nullable String error, int maxAttempts) {
        String status = jdbc.sql(LeasedJobSql.markFailedById("job", ClassifierJobRow.DEAD))
                .param("err", error)
                .param("now", Instant.now().toString())
                .param("id", id)
                .param("maxAttempts", maxAttempts)
                .query(String.class)
                .single();
        return ClassifierJobRow.DEAD.equals(status);
    }

    private static ClassifierJobRow map(ResultSet rs) throws SQLException {
        return new ClassifierJobRow(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getString("classifier_id"),
                rs.getString("status"),
                rs.getString("cursor_at"),
                rs.getString("cursor_id"),
                rs.getString("lease_owner"),
                rs.getString("lease_expires_at"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
