// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The usage reads run under a transaction-local statement timeout: a read that runs past it is a 422 the page can
 * show, the setting dies with the read's transaction, and any other database failure passes through unchanged.
 */
@SpringBootTest
record PromptUsageTimeoutTest(JdbcClient jdbc, TransactionTemplate transactions, PromptUsageRepository repository) {

    private String setting() {
        return jdbc.sql("SELECT current_setting('statement_timeout')")
                .query(String.class)
                .single();
    }

    @Test
    void aReadPastTheTimeoutIsUsageTimedOutNotA500() {
        // Bug: a slow usage read over a busy window runs on unbounded, or surfaces as a 500 with a stack.
        PromptUsageRepository timed = new PromptUsageRepository(jdbc, 50);

        TessaryException e = assertThrows(
                TessaryException.class,
                () -> transactions.executeWithoutResult(status ->
                        timed.timed(() -> jdbc.sql("SELECT pg_sleep(2)").query().listOfRows())));

        assertEquals(PromptsError.USAGE_TIMED_OUT, e.error());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.error().status());
    }

    @Test
    void theTimeoutAppliesInsideTheReadAndDiesWithItsTransaction() {
        // Bug: the setting is session-wide, so a pooled connection keeps a 5-second cap for every later query.
        String before = setting();
        String inside = transactions.execute(status -> repository.timed(this::setting));

        assertEquals(PromptUsageRepository.STATEMENT_TIMEOUT_MS / 1000 + "s", inside);
        assertEquals(before, setting());
    }

    @Test
    void anyOtherDatabaseFailurePassesThroughUnchanged() {
        // Bug: every SQL error is reported as a timeout, hiding a real defect behind "pick a shorter window".
        assertThrows(
                DataAccessException.class,
                () -> transactions.executeWithoutResult(status -> repository.timed(
                        () -> jdbc.sql("SELECT * FROM no_such_table").query().listOfRows())));
    }
}
