// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.jobqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LeasedJobSqlTest {

    @Test
    void claimBatchRejectsUnknownOrderColumn() {
        // orderByColumn is validated against a whitelist — a shared open-module util must not trust it.
        assertThrows(
                IllegalArgumentException.class,
                () -> LeasedJobSql.claimBatch("embedding_job", "id", "created_at; DROP TABLE embedding_job"));
    }

    @Test
    void claimBatchCarriesTheSkipLockedReclaimShape() {
        String sql = LeasedJobSql.claimBatch("embedding_job", "id, project_id, status", "updated_at");
        assertTrue(sql.contains("UPDATE embedding_job SET status = 'claimed'"), sql);
        assertTrue(sql.contains("FROM embedding_job"), sql);
        assertTrue(sql.contains("attempts = attempts + 1"), sql);
        assertTrue(sql.contains("status = 'claimed' AND lease_expires_at < :now AND attempts < :maxAttempts"), sql);
        assertTrue(sql.contains("ORDER BY updated_at"), sql);
        assertTrue(sql.contains("FOR UPDATE SKIP LOCKED"), sql);
        assertTrue(sql.contains("LIMIT :batch"), sql);
        assertTrue(sql.contains("RETURNING id, project_id, status"), sql);
    }

    @Test
    void failExhaustedTargetsExpiredOverCapWithReason() {
        String sql = LeasedJobSql.failExhausted("signal_job", "hung or crashed mid-sweep");
        assertTrue(sql.contains("UPDATE signal_job"), sql);
        assertTrue(sql.contains("status = 'failed'"), sql);
        assertTrue(sql.contains("attempts >= :maxAttempts"), sql);
        assertTrue(sql.contains("(hung or crashed mid-sweep)"), sql);
    }

    @Test
    void claimBatchOfKindRejectsUnknownOrderColumn() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LeasedJobSql.claimBatchOfKind("job", "id", "created_at; DROP TABLE job"));
    }

    @Test
    void claimBatchOfKindScopesTheClaimToOneKind() {
        String sql = LeasedJobSql.claimBatchOfKind("job", "id, project_id, status", "updated_at");
        assertTrue(sql.contains("UPDATE job SET status = 'claimed'"), sql);
        assertTrue(sql.contains("WHERE kind = :kind"), sql);
        // The status disjunction MUST be wrapped so the kind predicate can't let another kind's claimed job
        // leak into the reclaim leg (AND binds tighter than OR): the `kind =` AND is followed by an OPEN paren.
        assertTrue(sql.contains("AND (status = 'pending'"), sql);
        assertTrue(sql.contains("attempts < :maxAttempts))"), sql); // closes both the reclaim + the wrap paren
        assertTrue(sql.contains("FOR UPDATE SKIP LOCKED"), sql);
        assertTrue(sql.contains("RETURNING id, project_id, status"), sql);
    }

    @Test
    void failExhaustedOfKindScopesTheDeadLetterToOneKind() {
        String sql = LeasedJobSql.failExhaustedOfKind("job", "hung mid-embed");
        assertTrue(sql.contains("UPDATE job"), sql);
        assertTrue(sql.contains("WHERE kind = :kind AND status = 'claimed'"), sql);
        assertTrue(sql.contains("attempts >= :maxAttempts"), sql);
        assertTrue(sql.contains("(hung mid-embed)"), sql);
    }

    @Test
    void failExhaustedHonorsTheDeadTerminalStatus() {
        // An adopting queue's opt-in: lease-expiry exhaustion must land in the cooldown-gated 'dead' state,
        // not 'failed' (which the routine revive path would resurrect immediately — a no-backoff retry loop).
        String sql = LeasedJobSql.failExhaustedOfKind("job", "hung or crashed mid-sweep", "dead");
        assertTrue(sql.contains("status = 'dead'"), sql);
        assertTrue(sql.contains("WHERE kind = :kind AND status = 'claimed'"), sql);
        assertTrue(
                LeasedJobSql.failExhausted("embedding_job", "hung mid-embed", "dead")
                        .contains("status = 'dead'"),
                "the un-kinded variant honors the status too");
    }

    @Test
    void failExhaustedTwoArgOverloadsKeepTheFailedDefault() {
        // "Other job kinds' reclaim behavior is unchanged": the existing overloads stay on 'failed'.
        assertTrue(LeasedJobSql.failExhausted("embedding_job", "hung").contains("status = 'failed'"));
        assertTrue(LeasedJobSql.failExhaustedOfKind("job", "hung").contains("status = 'failed'"));
    }

    @Test
    void failExhaustedRejectsUnknownTerminalStatus() {
        // terminalStatus is validated against a whitelist — same defence in depth as orderByColumn.
        assertThrows(
                IllegalArgumentException.class,
                () -> LeasedJobSql.failExhausted("job", "hung", "pending'; DROP TABLE job --"));
        assertThrows(IllegalArgumentException.class, () -> LeasedJobSql.failExhaustedOfKind("job", "hung", "done"));
    }

    @Test
    void deadLetterCooldownGateIsTheExactParkedPredicate() {
        // A fragment, not a statement: the splice site provides the surrounding WHERE. Exact-string so a
        // consumer's composed statement is reviewable from this test plus the consumer's own SQL.
        assertEquals(
                "(job.status = 'dead' AND job.updated_at < :deadFloor)",
                LeasedJobSql.deadLetterCooldownGate("job", "dead"));
    }

    @Test
    void deadLetterCooldownGateRejectsUnknownParkedStatus() {
        // Same whitelist as failExhausted's terminalStatus — a shared open-module util must not trust it.
        assertThrows(
                IllegalArgumentException.class,
                () -> LeasedJobSql.deadLetterCooldownGate("job", "pending'; DROP TABLE job --"));
        assertThrows(IllegalArgumentException.class, () -> LeasedJobSql.deadLetterCooldownGate("job", "done"));
    }
}
