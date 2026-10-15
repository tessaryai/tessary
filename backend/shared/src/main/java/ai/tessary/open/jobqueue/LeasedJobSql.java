// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.jobqueue;

import java.util.Locale;

/**
 * The single source of the claim and dead-letter SQL for leased job kinds. Every argument is a code-level constant,
 * never request input.
 */
public final class LeasedJobSql {

    private LeasedJobSql() {}

    /**
     * Due when {@code pending}, or {@code claimed} with an expired lease under the attempt cap. The status disjunction
     * is parenthesised so another kind's claimed job cannot leak into the reclaim leg.
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

    static final int PREVIOUS_ERROR_CHARS = 500;

    /**
     * Carries the last attempt's error forward, since the phrase alone erased why the attempts failed. It cannot
     * accumulate: dead-letter statements match only {@code claimed} rows.
     */
    public static String exhaustedLastError(String reason) {
        return "'exhausted: ' || attempts || ' attempts (" + reason + ")'"
                + " || COALESCE('; last: ' || left(last_error, " + PREVIOUS_ERROR_CHARS + "), '')";
    }

    /** A queue whose exhausted jobs must not be revived immediately passes {@code dead}. */
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
     * A WHERE fragment, the one exception to this builder's whole-statement rule. Single-sourced so the gate and the
     * park statements cannot drift apart: an exhausted job gets one revival per cooldown window, not an every-touch
     * retry loop.
     */
    public static String deadLetterCooldownGate(String rowQualifier, String parkedStatus) {
        return String.format(
                Locale.ROOT, "(%1$s.status = '%2$s' AND %1$s.updated_at < :deadFloor)", rowQualifier, parkedStatus);
    }

    /** Returns the resulting {@code status} so the caller can tell whether this call crossed the cap. */
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
