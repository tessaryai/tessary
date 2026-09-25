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
 * The leased queue for Layer-2 triage on the unified {@code job} table ({@code kind='triage'},
 * {@code dedupe_key = <project>:<finding>}, backed by the unique {@code ux_job_triage}).
 *
 * <p>A separate kind from {@code grader_run} because it is a different question. The call site's
 * grader set judges whether the answer was good; this asks whether the detector's claim is true,
 * measured over enough of the population, and carried by the evidence it cites. A passing grader
 * says nothing about either, since an agent can answer well on traces a claim was mis-measured over,
 * so routing findings through the grader queue would audit them with the wrong instrument.
 *
 * <p>Finite-job grain like the RCA queue: no cursor. The dedupe key covers every status rather than
 * pending-only, so a finding gets exactly one triage run: the ruling is about the cause, and a later
 * sweep bumping the counter does not make the cause new. A finding's cause firing again after a
 * ruling opens a FRESH finding — a new id, a new dedupe key — rather than reopening this one, so there
 * is no "second look" to key here at all.
 */
@Repository
public class BehaviorTriageJobRepository {

    /** A persisted value; {@code job_kind_check} and the partial unique index both name it. */
    static final String KIND = "triage";

    private static final String COLS = "id, project_id, payload->>'finding_id' AS finding_id, "
            + "status, lease_owner, lease_expires_at, attempts, last_error, created_at, updated_at";

    /** True for a look parked in {@code dead} whose cooldown floor has passed; see {@link #REVIVE_IF_COOLED}. */
    private static final String REVIVABLE = LeasedJobSql.deadLetterCooldownGate("job", BehaviorTriageJobRow.DEAD);

    /**
     * The {@code ON CONFLICT DO UPDATE} body that revives a dead-lettered look, and leaves every other
     * status exactly as it found it.
     *
     * <p><b>Why {@code CASE} rather than a {@code WHERE} on the update.</b> Postgres returns NO row from
     * {@code ON CONFLICT DO UPDATE ... WHERE cond RETURNING} when {@code cond} is false, and
     * {@link #enqueue} reads the conflicting row's id and status back through {@code RETURNING ... .single()}. The
     * unconditional no-op self-assignment is what makes the row come back at all; the revival rides
     * inside it as a branch rather than beside it as a filter.
     *
     * <p>{@code lease_owner} and {@code lease_expires_at} are deliberately left alone: {@code claimBatch}
     * overwrites both the moment it claims the revived row, and its {@code pending} arm consults
     * neither.
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

    /** The moment a dead-lettered look becomes revivable; bound as {@code :deadFloor} by {@link #enqueue}. */
    private String deadFloor() {
        return Instant.now()
                .minus(Duration.ofSeconds(props.getDeadLetterCooldownSeconds()))
                .toString();
    }

    /** The enqueue outcome: the job this finding resolved to, and whether it was already queued. */
    public record EnqueueOutcome(String jobId, String jobStatus) {}

    /**
     * Enqueue the triage for one finding, or resolve to the job it already has. Race-safe via
     * the no-op-update-with-{@code RETURNING} upsert. The dedupe key starts with the project id
     * because {@code ux_job_triage} is a global index, so two projects' findings must
     * never coalesce onto one job.
     *
     * <p>Unlike the RCA queue this does not revive a job on every touch: triage is advisory evidence on
     * a finding, and a finding whose analysis failed should not re-spend a microVM on every subsequent
     * sweep. Re-running is a deliberate human action, and {@link #REVIVE_IF_COOLED} is what makes that
     * sentence true rather than aspirational. A dead-lettered job past its cooldown floor goes back to
     * {@code pending} with a fresh attempt budget; every other status is left untouched, exactly as
     * before. The automatic lane cannot reach this branch at all: {@code FindingRepository}'s escalation
     * sweep requires {@code escalated_at IS NULL}, and a finding with a dead-lettered job has it set.
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

    /** Claim up to {@code batch} due jobs via {@code FOR UPDATE SKIP LOCKED}, oldest-first. */
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
     * Dead-letter jobs whose lease expired with attempts at/over the cap (hung or crashed mid-analysis).
     *
     * <p>Terminal status is the cooldown-gated {@code dead}, not the shared default {@code failed}: a
     * {@code failed} triage was reachable by nothing at all: not the escalation sweep (it wants
     * {@code escalated_at IS NULL}), not the recurrence re-open (it wants a {@code closed} ruling a
     * never-triaged finding never has), and not the enqueue path, which had no revival branch. The
     * finding stayed un-triaged with a NULL verdict for good. {@code dead} keeps the "don't re-spend a
     * microVM on every sweep" guarantee that motivated the terminal state, while leaving
     * {@link #enqueue}'s cooldown gate as one way back.
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
     * The dead-lettered triage of each of these findings, keyed by finding id.
     *
     * <p>Read on the findings page so a run that gave up is distinguishable from one still going. Both
     * leave {@code triaged_at} NULL with {@code escalated_at} set, so from the {@code finding} table
     * alone they are the same row, which is why a permanently-failed triage rendered as "Triaging"
     * indefinitely. This is the one fact the finding does not carry.
     *
     * <p>Only dead-lettered rows: a job still retrying is in flight, and reporting its interim
     * {@code last_error} would flap the surface between "failed" and "running" on every attempt. That
     * also means a job parked waiting on a missing credential reads as in-flight rather than failed,
     * which is honest: it has spent no attempt and will run itself the moment the credential lands.
     *
     * <p>One query for the page rather than one per row: the alternative is an N+1 on the busiest
     * surface the classifiers have.
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

    /** A triage that gave up: how many attempts it spent, and what the last one said. */
    public record FailedTriage(
            String findingId, int attempts, @Nullable String lastError) {}

    public void markDone(String id) {
        jdbc.sql("UPDATE job SET status = 'done', last_error = NULL, updated_at = :now WHERE id = :id")
                .param("now", Instant.now().toString())
                .param("id", id)
                .update();
    }

    /**
     * Hand a failed run back to the queue: keep it {@code claimed}, expire its lease {@code delaySeconds}
     * from now, and record why.
     *
     * <p><b>Not {@code failed}.</b> A triage that did not produce a ruling leaves {@code triage_verdict}
     * NULL, and a terminal job would strand that finding un-triaged forever, invisible because nothing
     * distinguishes it from one nobody has scheduled. Expiring the lease puts the row straight back in
     * {@link #claimBatch}'s reclaim leg while attempts remain, and {@link #failExhausted} dead-letters it
     * once they do not. Status is left alone rather than set to {@code pending} on purpose: the pending
     * arm of the claim does not consult {@code attempts}, so a released job would retry forever.
     *
     * <p>The delay exists because immediate expiry meant the next tick re-claimed at once, so a job that
     * fails deterministically spent all {@code maxAttempts} as fast as the scheduler could turn, with no
     * pause in which the thing it depends on might recover. The delay is the difference between a retry and a
     * spin.
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
     * Hand the job back WITHOUT spending the attempt: the lease expires now and {@code attempts} is
     * decremented to undo the increment {@code claimBatch} made.
     *
     * <p>For failures that were never about this job. A launcher refusing every request is not five
     * chances to rule on a finding, it is zero, and letting it consume them dead-letters a queue of
     * perfectly good findings for a reason that had nothing to do with any of them. Floored at zero so
     * a double release cannot drive the count negative.
     */
    public void releaseWithoutAttempt(String id, @Nullable String error) {
        releaseWithoutAttempt(id, error, 0L);
    }

    /**
     * As {@link #releaseWithoutAttempt(String, String)}, but the lease expires {@code delaySeconds} from
     * now rather than immediately.
     *
     * <p>Immediate release is right for a launcher blip, where the breaker owns the pacing and the next
     * tick is a fair probe. It is wrong for a condition only a human can clear (a missing provider
     * credential), because the job would then be re-claimed, re-checked and re-released on every single
     * tick, forever. Worse, {@code claimBatch} takes {@code CLAIM_BATCH} rows per round in
     * {@code updated_at} order, so a backlog of permanently-unrunnable jobs would keep filling those
     * rounds and push genuinely runnable findings behind it. Not being due is what keeps them out of the
     * drain entirely until the window elapses.
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

    /** The key one finding's triage claims — {@code <project>:<finding>}, the whole of its scope. */
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
