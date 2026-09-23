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
    void everyDeadLetterVariantCarriesThePreviousError() {
        // The exhausted phrase alone erased why the attempts failed; each variant must keep it.
        String carried = LeasedJobSql.exhaustedLastError("hung");
        assertEquals(
                "'exhausted: ' || attempts || ' attempts (hung)' || COALESCE('; last: ' || left(last_error, 500), '')",
                carried);
        assertTrue(LeasedJobSql.failExhausted("job", "hung", "dead").contains(carried));
        assertTrue(LeasedJobSql.failExhaustedOfKind("job", "hung").contains(carried));
        assertTrue(LeasedJobSql.failExhaustedOfKind("job", "hung", "dead").contains(carried));
    }

    @Test
    void claimBatchOfKindRejectsUnknownOrderColumn() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LeasedJobSql.claimBatchOfKind("job", "id", "created_at; DROP TABLE job"));
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
    void deadLetterCooldownGateRejectsUnknownParkedStatus() {
        // Same whitelist as failExhausted's terminalStatus — a shared open-module util must not trust it.
        assertThrows(
                IllegalArgumentException.class,
                () -> LeasedJobSql.deadLetterCooldownGate("job", "pending'; DROP TABLE job --"));
        assertThrows(IllegalArgumentException.class, () -> LeasedJobSql.deadLetterCooldownGate("job", "done"));
    }
}
