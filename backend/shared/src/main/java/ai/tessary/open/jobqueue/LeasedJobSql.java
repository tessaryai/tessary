// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.jobqueue;

import java.util.Locale;
import java.util.Set;

/**
 * The SKIP-LOCKED claim / dead-letter SQL shared verbatim by the leased job queues
 * (embedding / signal / observer / synth). The statements differ only by table name, {@code RETURNING}
 * column list, drain-order column, and the dead-letter message phrase; this builder is the single
 * source of that SQL for the {@code LeasedJobQueue} machinery. (Excluded by design: the {@code pull}
 * kind — no lease claim; and the {@code usage_rollup} kind — a different {@code claimed_at} model
 * with no attempts/dead-letter.)
 *
 * <p>Pure string construction, no I/O. The {@code table} / {@code returningColumns} / {@code reason}
 * arguments are code-level constants supplied by each repository (never request input). As defence in
 * depth for a shared open-module utility, {@code orderByColumn} is validated against a whitelist rather
 * than trusted; callers pass the resulting string to {@code JdbcClient} with named params
 * ({@code :owner, :expires, :now, :maxAttempts, :batch}) exactly as the hand-written copies did.
 */
public final class LeasedJobSql {

    /** Drain-order columns a queue may sort its claim by (whitelist — see {@link #claimBatch}). */
    private static final Set<String> ALLOWED_ORDER_COLUMNS = Set.of("updated_at", "created_at");

    /**
     * Terminal statuses the dead-letter statement may write (whitelist — see {@link #failExhausted}).
     * {@code failed} is the shared default; {@code dead} is the signal queue's cooldown-gated
     * cap-crossing state (its enqueue path revives a {@code dead} row only after a cooldown floor,
     * not on the next heartbeat).
     */
    private static final Set<String> ALLOWED_TERMINAL_STATUSES = Set.of("failed", "dead");

    private LeasedJobSql() {}

    /**
     * The claim statement: atomically flip up to {@code :batch} due jobs to {@code claimed} under a
     * fresh lease, incrementing {@code attempts}. A job is due when {@code pending}, or {@code claimed}
     * with an expired lease while still under the attempt cap (crash reclaim). {@code FOR UPDATE SKIP
     * LOCKED} lets concurrent workers drain the queue without contending on the same rows; oldest
     * {@code orderByColumn} first drains the backlog FIFO-ish.
     *
     * @param table the queue table (e.g. {@code embedding_job})
     * @param returningColumns the row projection to return (the repository's {@code COLS})
     * @param orderByColumn the drain-order column — {@code updated_at} for most queues, {@code
     *     created_at} for the observer queue
     */
    public static String claimBatch(String table, String returningColumns, String orderByColumn) {
        if (!ALLOWED_ORDER_COLUMNS.contains(orderByColumn)) {
            throw new IllegalArgumentException("unsupported orderByColumn: " + orderByColumn);
        }
        return String.format(Locale.ROOT, """
                UPDATE %1$s SET status = 'claimed', lease_owner = :owner,
                                lease_expires_at = :expires, attempts = attempts + 1, updated_at = :now
                WHERE id IN (
                    SELECT id FROM %1$s
                    WHERE status = 'pending'
                       OR (status = 'claimed' AND lease_expires_at < :now AND attempts < :maxAttempts)
                    ORDER BY %3$s
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batch
                )
                RETURNING %2$s
                """, table, returningColumns, orderByColumn);
    }

    /**
     * The kind-scoped claim: identical to {@link #claimBatch} but restricted to one {@code kind} in the
     * unified {@code job} table — binds an extra {@code :kind} param and wraps the status disjunction
     * so the {@code kind =} predicate can never let a claimed job of another kind leak into the reclaim leg.
     */
    public static String claimBatchOfKind(String table, String returningColumns, String orderByColumn) {
        if (!ALLOWED_ORDER_COLUMNS.contains(orderByColumn)) {
            throw new IllegalArgumentException("unsupported orderByColumn: " + orderByColumn);
        }
        return String.format(Locale.ROOT, """
                UPDATE %1$s SET status = 'claimed', lease_owner = :owner,
                                lease_expires_at = :expires, attempts = attempts + 1, updated_at = :now
                WHERE id IN (
                    SELECT id FROM %1$s
                    WHERE kind = :kind
                      AND (status = 'pending'
                           OR (status = 'claimed' AND lease_expires_at < :now AND attempts < :maxAttempts))
                    ORDER BY %3$s
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batch
                )
                RETURNING %2$s
                """, table, returningColumns, orderByColumn);
    }

    /**
     * The dead-letter statement: mark {@code failed} any job whose lease expired with {@code attempts}
     * at or over the cap (hung or crashed past its retry budget).
     *
     * @param table the queue table
     * @param reason a short human phrase for the {@code last_error} message (e.g. {@code hung or crashed
     *     mid-embed})
     */
    public static String failExhausted(String table, String reason) {
        return failExhausted(table, reason, "failed");
    }

    /**
     * Like {@link #failExhausted(String, String)} but writing an explicit terminal status. A queue whose
     * exhausted jobs must not be revived immediately opts in here by passing the cooldown-gated
     * {@code dead} state (the signal and embedding queues today); {@code terminalStatus} is a
     * code-level constant, validated against
     * {@link #ALLOWED_TERMINAL_STATUSES} with the same defence-in-depth as {@code orderByColumn}.
     */
    public static String failExhausted(String table, String reason, String terminalStatus) {
        if (!ALLOWED_TERMINAL_STATUSES.contains(terminalStatus)) {
            throw new IllegalArgumentException("unsupported terminalStatus: " + terminalStatus);
        }
        return String.format(Locale.ROOT, """
                UPDATE %s
                   SET status = '%s',
                       last_error = 'exhausted: ' || attempts || ' attempts (%s)',
                       updated_at = :now
                 WHERE status = 'claimed' AND lease_expires_at < :now AND attempts >= :maxAttempts
                """, table, terminalStatus, reason);
    }

    /**
     * The kind-scoped dead-letter: like {@link #failExhausted} but restricted to one {@code kind} in the
     * unified {@code job} table. Binds an extra {@code :kind} param.
     */
    public static String failExhaustedOfKind(String table, String reason) {
        return failExhaustedOfKind(table, reason, "failed");
    }

    /**
     * Like {@link #failExhaustedOfKind(String, String)} but writing an explicit terminal status (see
     * {@link #failExhausted(String, String, String)} — same whitelist, same opt-in semantics).
     */
    public static String failExhaustedOfKind(String table, String reason, String terminalStatus) {
        if (!ALLOWED_TERMINAL_STATUSES.contains(terminalStatus)) {
            throw new IllegalArgumentException("unsupported terminalStatus: " + terminalStatus);
        }
        return String.format(Locale.ROOT, """
                UPDATE %s
                   SET status = '%s',
                       last_error = 'exhausted: ' || attempts || ' attempts (%s)',
                       updated_at = :now
                 WHERE kind = :kind AND status = 'claimed' AND lease_expires_at < :now AND attempts >= :maxAttempts
                """, table, terminalStatus, reason);
    }

    /**
     * The lease-renewal statement: push one claimed job's {@code lease_expires_at} out, but <b>only if
     * this worker still owns it</b>. Returns the job id, so 0 rows means the caller has LOST the lease
     * — another worker's reclaim already took the job.
     *
     * <p><b>Why this exists.</b> {@link #claimBatch}/{@link #claimBatchOfKind} re-claim any {@code
     * claimed} job whose lease expired while still under the attempt cap, and a running worker has no
     * way to notice. Without renewal, a job that legitimately outruns its lease is restarted from the
     * top <em>while the first worker is still running it</em>: both do the full work, and the loser's
     * writes are silently swallowed by the queues' {@code ON CONFLICT DO NOTHING} inserts. For LLM
     * grading that is duplicated spend, invisible at the DB layer. Long-running workers call this at a
     * per-item checkpoint and abandon the job the moment it returns false.
     *
     * <p>The {@code lease_owner = :owner} predicate is the load-bearing part: it is what turns
     * "renew my lease" into "renew my lease, or tell me I no longer hold it". Binds {@code :id,
     * :owner, :expires, :now}.
     *
     * @param table the queue table
     */
    public static String renewLease(String table) {
        return String.format(Locale.ROOT, """
                UPDATE %s
                   SET lease_expires_at = :expires, updated_at = :now
                 WHERE id = :id AND lease_owner = :owner AND status = 'claimed'
                RETURNING id
                """, table);
    }

    /**
     * The dead-letter cooldown gate — a WHERE-clause <b>fragment, not a runnable statement</b> (the one
     * deliberate exception to this builder's whole-statement rule): true for a row parked in
     * {@code parkedStatus} whose park moment ({@code updated_at} — every park statement stamps it) is
     * older than the caller-bound {@code :deadFloor}. A queue whose routine enqueue path revives terminal
     * rows splices this in as the <em>only</em> way back from the parked state, so a job that exhausted its
     * attempt budget gets one bounded revival per cooldown window instead of an every-touch retry loop —
     * single-sourced here so, for adopting queues, the gate and the park statements
     * ({@link #failExhausted(String, String, String)}, {@link #markFailedById(String, String)}) cannot
     * drift apart. The cooldown length is whatever the caller derives
     * {@code :deadFloor} from (per-call today; the seam for per-project overrides).
     *
     * @param rowQualifier the row reference the splice site sees (e.g. the table name inside an
     *     {@code ON CONFLICT ... DO UPDATE ... WHERE}); a code-level constant, like {@code table}
     * @param parkedStatus the parked status the gate guards, validated against
     *     {@link #ALLOWED_TERMINAL_STATUSES} with the same defence-in-depth as {@code orderByColumn}
     */
    public static String deadLetterCooldownGate(String rowQualifier, String parkedStatus) {
        if (!ALLOWED_TERMINAL_STATUSES.contains(parkedStatus)) {
            throw new IllegalArgumentException("unsupported parkedStatus: " + parkedStatus);
        }
        return String.format(
                Locale.ROOT, "(%1$s.status = '%2$s' AND %1$s.updated_at < :deadFloor)", rowQualifier, parkedStatus);
    }

    /**
     * The fast-fail park statement: mark one job {@code failed} by id, or the given {@code
     * terminalStatus} instead if this failure crosses {@code :maxAttempts} (a cap-crossing park, not a
     * bulk lease-expiry sweep like {@link #failExhausted(String, String, String)} — no lease-expiry
     * condition, since the caller already knows which single job just failed). Returns the resulting
     * {@code status} column so the caller can tell whether this call is the one that crossed the cap.
     *
     * @param table the queue table
     * @param terminalStatus the cap-crossing terminal status, validated against {@link
     *     #ALLOWED_TERMINAL_STATUSES} with the same defence-in-depth as {@code orderByColumn}
     */
    public static String markFailedById(String table, String terminalStatus) {
        if (!ALLOWED_TERMINAL_STATUSES.contains(terminalStatus)) {
            throw new IllegalArgumentException("unsupported terminalStatus: " + terminalStatus);
        }
        return String.format(Locale.ROOT, """
                UPDATE %1$s
                   SET status = CASE WHEN attempts >= :maxAttempts THEN '%2$s' ELSE 'failed' END,
                       last_error = :err,
                       updated_at = :now
                 WHERE id = :id
                RETURNING status
                """, table, terminalStatus);
    }
}
