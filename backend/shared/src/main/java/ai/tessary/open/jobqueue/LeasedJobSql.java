// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.jobqueue;

import java.util.Locale;

/**
 * The SKIP-LOCKED claim / dead-letter SQL shared verbatim by the leased kinds of the unified {@code job}
 * table. The statements differ only by {@code RETURNING} column list, drain-order column, and the
 * dead-letter message phrase; this builder is the single source of that SQL. (Excluded by design: the
 * {@code usage_rollup} kind — a different {@code claimed_at} model with no attempts/dead-letter.)
 *
 * <p>Pure string construction, no I/O. Every argument is a code-level constant supplied by each
 * repository (never request input); callers pass the resulting string to {@code JdbcClient} with named
 * params ({@code :owner, :expires, :now, :maxAttempts, :batch, :kind}).
 */
public final class LeasedJobSql {

    private LeasedJobSql() {}

    /**
     * The kind-scoped claim: atomically flip up to {@code :batch} due jobs of one {@code kind} to
     * {@code claimed} under a fresh lease, incrementing {@code attempts}. A job is due when {@code
     * pending}, or {@code claimed} with an expired lease while still under the attempt cap (crash
     * reclaim). {@code FOR UPDATE SKIP LOCKED} lets concurrent workers drain the queue without contending
     * on the same rows; oldest {@code orderByColumn} first drains the backlog FIFO-ish. The status
     * disjunction is wrapped so the {@code kind =} predicate can never let a claimed job of another kind
     * leak into the reclaim leg.
     *
     * @param table the queue table
     * @param returningColumns the row projection to return (the repository's {@code COLS})
     * @param orderByColumn the drain-order column, {@code updated_at} or {@code created_at}
     */
    public static String claimBatchOfKind(String table, String returningColumns, String orderByColumn) {
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

    /** How much of the previous {@code last_error} a dead-letter message carries forward. */
    static final int PREVIOUS_ERROR_CHARS = 500;

    /**
     * The {@code last_error} expression a dead-letter writes: the exhausted phrase, then the error the
     * last attempt recorded when there is one (e.g. {@code exhausted: 5 attempts (hung or crashed
     * mid-triage); last: launcher failed kind=timeout}).
     *
     * <p>The phrase alone erased the one fact a reader of a dead job needs: why the attempts failed. The
     * carried error is bounded so the message stays readable. It cannot accumulate across sweeps
     * because every dead-letter statement only matches {@code claimed} rows, and it moves each match out
     * of that state.
     *
     * @param reason the same code-level constant phrase {@link #failExhaustedOfKind} takes
     */
    public static String exhaustedLastError(String reason) {
        return "'exhausted: ' || attempts || ' attempts (" + reason + ")'"
                + " || COALESCE('; last: ' || left(last_error, " + PREVIOUS_ERROR_CHARS + "), '')";
    }

    /**
     * The kind-scoped dead-letter: mark {@code terminalStatus} any job of one {@code kind} whose lease
     * expired with {@code attempts} at or over the cap (hung or crashed past its retry budget). Binds an
     * extra {@code :kind} param. A queue whose exhausted jobs must not be revived immediately passes the
     * cooldown-gated {@code dead} state.
     *
     * @param table the queue table
     * @param reason a short human phrase for the {@code last_error} message (e.g. {@code hung or crashed
     *     mid-sweep})
     * @param terminalStatus the terminal status to write, {@code failed} or {@code dead}
     */
    public static String failExhaustedOfKind(String table, String reason, String terminalStatus) {
        return String.format(Locale.ROOT, """
                UPDATE %s
                   SET status = '%s',
                       last_error = %s,
                       updated_at = :now
                 WHERE kind = :kind AND status = 'claimed' AND lease_expires_at < :now AND attempts >= :maxAttempts
                """, table, terminalStatus, exhaustedLastError(reason));
    }

    /**
     * The dead-letter cooldown gate — a WHERE-clause <b>fragment, not a runnable statement</b> (the one
     * deliberate exception to this builder's whole-statement rule): true for a row parked in
     * {@code parkedStatus} whose park moment ({@code updated_at} — every park statement stamps it) is
     * older than the caller-bound {@code :deadFloor}. A queue whose routine enqueue path revives terminal
     * rows splices this in as the <em>only</em> way back from the parked state, so a job that exhausted its
     * attempt budget gets one bounded revival per cooldown window instead of an every-touch retry loop —
     * single-sourced here so, for adopting queues, the gate and the park statements
     * ({@link #failExhaustedOfKind(String, String, String)}, {@link #markFailedById(String, String)}) cannot
     * drift apart. The cooldown length is whatever the caller derives
     * {@code :deadFloor} from (per-call today; the seam for per-project overrides).
     *
     * @param rowQualifier the row reference the splice site sees (e.g. the table name inside an
     *     {@code ON CONFLICT ... DO UPDATE ... WHERE}); a code-level constant, like {@code table}
     * @param parkedStatus the parked status the gate guards, {@code failed} or {@code dead}
     */
    public static String deadLetterCooldownGate(String rowQualifier, String parkedStatus) {
        return String.format(
                Locale.ROOT, "(%1$s.status = '%2$s' AND %1$s.updated_at < :deadFloor)", rowQualifier, parkedStatus);
    }

    /**
     * The fast-fail park statement: mark one job {@code failed} by id, or the given {@code
     * terminalStatus} instead if this failure crosses {@code :maxAttempts} (a cap-crossing park, not a
     * bulk lease-expiry sweep like {@link #failExhaustedOfKind(String, String, String)} — no lease-expiry
     * condition, since the caller already knows which single job just failed). Returns the resulting
     * {@code status} column so the caller can tell whether this call is the one that crossed the cap.
     *
     * @param table the queue table
     * @param terminalStatus the cap-crossing terminal status, {@code failed} or {@code dead}
     */
    public static String markFailedById(String table, String terminalStatus) {
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
