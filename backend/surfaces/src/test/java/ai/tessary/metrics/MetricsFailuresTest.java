// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;

class MetricsFailuresTest {

    /** The bug: a statement cancelled by its timeout answers 500 instead of telling the user to narrow the window. */
    @Test
    @DisplayName("SQL state 57014 anywhere in the cause chain becomes TIMED_OUT, keeping the cause")
    void sqlState57014AnywhereInTheCauseChainBecomesTimedOut() {
        SQLException cancelled = new SQLException("canceling statement due to statement timeout", "57014");
        QueryTimeoutException failure =
                new QueryTimeoutException("read failed", new IllegalStateException("wrapped", cancelled));

        RuntimeException out = MetricsFailures.translate(failure);

        TessaryException ex = (TessaryException) out;
        assertEquals(MetricsError.TIMED_OUT, ex.error());
        assertEquals("Query took too long. Narrow the window or pick a coarser granularity.", ex.getMessage());
        assertSame(failure, ex.getCause());
    }

    /** The bug: every database failure reported as a timeout, hiding a broken query behind "narrow the window". */
    @Test
    @DisplayName("any other failure comes back unchanged")
    void anyOtherFailureComesBackUnchanged() {
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("connection lost", new SQLException("connection lost", "08006"));
        assertSame(failure, MetricsFailures.translate(failure));

        DataAccessResourceFailureException bare = new DataAccessResourceFailureException("no cause");
        assertSame(bare, MetricsFailures.translate(bare));
    }
}
