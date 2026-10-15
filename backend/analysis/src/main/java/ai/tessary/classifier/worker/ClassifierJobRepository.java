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
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A classifier job is a resumable sweep, not a finite job: its {@code cursor_at}/{@code cursor_id} keyset high-water
 * mark advances after each sweep and the job re-pends on the next heartbeat. {@code classifier_id} lives in the
 * payload; {@code ux_job_classifier} keeps one row per classifier.
 */
@Repository
public class ClassifierJobRepository {

    private static final String COLS = "id, project_id, payload->>'classifier_id' AS classifier_id, status, cursor_at, "
            + "cursor_id, lease_owner, lease_expires_at, attempts, last_error, created_at, updated_at, page_retries";

    private final JdbcClient jdbc;

    public ClassifierJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A {@code dead} job is not revived on every call: {@link ClassifierService#enqueueEnabled} calls this on every
     * heartbeat, so immediate revival would re-enter the fast-fail loop the cap exists to stop. It is revived once
     * {@code deadLetterCooldownSeconds} has elapsed.
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
     * A {@code dead} job only qualifies once its {@code updated_at} is older than {@code deadLetterCooldownSeconds}.
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

    /** A poison job whose lease expired is left for {@link #failExhausted}. */
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

    public List<ClassifierJobRow> listByProject(String projectId) {
        return jdbc.sql("SELECT " + COLS + " FROM job WHERE kind = :kind AND project_id = :pid")
                .param("kind", JobRow.Kind.CLASSIFIER)
                .param("pid", projectId)
                .query((rs, n) -> map(rs))
                .list();
    }

    /**
     * Terminal status is {@code dead}, not {@code failed}, so a sweep that keeps hanging waits out the dead-letter
     * cooldown instead of being re-pended by the next heartbeat.
     */
    public int failExhausted(int maxAttempts) {
        return jdbc.sql(LeasedJobSql.failExhaustedOfKind("job", "hung or crashed mid-sweep", ClassifierJobRow.DEAD))
                .param("kind", JobRow.Kind.CLASSIFIER)
                .param("now", Instant.now().toString())
                .param("maxAttempts", maxAttempts)
                .update();
    }

    /**
     * A null cursor leaves the high-water mark untouched. {@code attempts} resets to 0 so the cap measures consecutive
     * failures, not a lifetime total.
     */
    public void markSwept(String id, @Nullable String cursorAt, @Nullable String cursorId) {
        jdbc.sql("""
            UPDATE job SET status = 'done',
                           cursor_at = COALESCE(:cursorAt, cursor_at),
                           cursor_id = COALESCE(:cursorId, cursor_id),
                           attempts = 0, page_retries = 0,
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
     * The page's provider calls mostly failed, so the next tick sends it again. A provider outage is not the sweep
     * failing, so the attempt budget is untouched.
     */
    public void holdPage(String id) {
        jdbc.sql("""
            UPDATE job SET status = 'done', page_retries = page_retries + 1,
                           attempts = 0, last_error = NULL, updated_at = :now
            WHERE id = :id
            """).param("now", Instant.now().toString()).param("id", id).update();
    }

    /**
     * Renews the lease so a long drain is not reclaimed mid-run. False means this worker no longer holds the job, and
     * the caller must stop.
     */
    public boolean advanceCursor(String id, String leaseOwner, String cursorAt, String cursorId, long leaseSeconds) {
        Instant now = Instant.now();
        return jdbc.sql("UPDATE job SET cursor_at = :cursorAt, cursor_id = :cursorId, page_retries = 0,"
                        + " lease_expires_at = :expires, updated_at = :now"
                        + " WHERE id = :id AND lease_owner = :owner AND status = 'claimed'"
                        + " RETURNING id")
                .param("cursorAt", cursorAt)
                .param("cursorId", cursorId)
                .param("expires", now.plus(Duration.ofSeconds(leaseSeconds)).toString())
                .param("now", now.toString())
                .param("id", id)
                .param("owner", leaseOwner)
                .query(String.class)
                .optional()
                .isPresent();
    }

    /**
     * The model is down, which is not the sweep failing, so no attempt is spent. Finishes {@code done}, not
     * {@code pending}: a pending job would be claimed again by the same tick and handed straight back.
     */
    public void releaseWithoutAttempt(String id, String leaseOwner) {
        jdbc.sql("""
            UPDATE job SET status = 'done', lease_owner = NULL, lease_expires_at = NULL,
                           attempts = GREATEST(attempts - 1, 0), updated_at = :now
            WHERE id = :id AND lease_owner = :owner
            """)
                .param("now", Instant.now().toString())
                .param("id", id)
                .param("owner", leaseOwner)
                .update();
    }

    /**
     * Merged into the payload so {@code classifier_id} stays. In production an encoder-backed classifier is not
     * enqueued again until the sleep after this has passed.
     */
    public void recordCaughtUp(String id, String leaseOwner, Instant at) {
        jdbc.sql("""
            UPDATE job SET payload = COALESCE(payload, '{}'::jsonb)
                                     || jsonb_build_object('caught_up_at', CAST(:at AS text))
            WHERE id = :id AND lease_owner = :owner
            """)
                .param("at", at.toString())
                .param("id", id)
                .param("owner", leaseOwner)
                .update();
    }

    public Optional<Instant> caughtUpAt(String projectId, String classifierId) {
        return jdbc.sql("""
            SELECT payload->>'caught_up_at' FROM job
            WHERE kind = 'classifier' AND project_id = :pid AND dedupe_key = :sid
            """).param("pid", projectId).param("sid", classifierId).query(String.class).list().stream()
                .filter(Objects::nonNull)
                .findFirst()
                .map(Instant::parse);
    }

    public Optional<ClassifierJobRow> findByClassifier(String projectId, String classifierId) {
        return jdbc.sql("SELECT " + COLS + " FROM job WHERE kind = :kind AND project_id = :pid AND dedupe_key = :sid")
                .param("kind", JobRow.Kind.CLASSIFIER)
                .param("pid", projectId)
                .param("sid", classifierId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    /**
     * The one operation {@link #markSwept} cannot express, since its {@code COALESCE(:cursorAt, cursor_at)} only moves
     * forward. A {@code claimed} job is skipped because it would markSwept straight back over the rewind; a
     * {@code dead} job is skipped so this cannot bypass the dead-letter cooldown.
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

    public enum Restart {
        RESTARTED,
        NO_JOB,
        SWEEP_RUNNING
    }

    /**
     * Unlike {@link #rewindCursor} this revives a {@code dead} job. Call it inside the transaction that clears the
     * classifier's output: the row stays locked, and {@link #claimBatch} skips locked rows.
     */
    public Restart restart(String projectId, String classifierId) {
        Optional<String> status = jdbc.sql("""
            SELECT status FROM job
            WHERE kind = 'classifier' AND project_id = :pid AND dedupe_key = :sid
            FOR UPDATE
            """)
                .param("pid", projectId)
                .param("sid", classifierId)
                .query(String.class)
                .optional();
        if (status.isEmpty()) return Restart.NO_JOB;
        if (ClassifierJobRow.CLAIMED.equals(status.get())) return Restart.SWEEP_RUNNING;
        jdbc.sql("""
            UPDATE job SET cursor_at = NULL, cursor_id = NULL, status = 'pending',
                           attempts = 0, page_retries = 0, last_error = NULL,
                           lease_owner = NULL, lease_expires_at = NULL,
                           payload = COALESCE(payload, '{}'::jsonb) - 'caught_up_at', updated_at = :now
            WHERE kind = 'classifier' AND project_id = :pid AND dedupe_key = :sid
            """)
                .param("now", Instant.now().toString())
                .param("pid", projectId)
                .param("sid", classifierId)
                .update();
        return Restart.RESTARTED;
    }

    /**
     * At the cap the job moves to {@code dead}, which the heartbeat re-pend won't revive until its cooldown. Returns
     * true only for the call that crossed the cap.
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
                rs.getString("updated_at"),
                rs.getInt("page_retries"));
    }
}
