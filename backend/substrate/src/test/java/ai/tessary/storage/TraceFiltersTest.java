// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class TraceFiltersTest {

    /** Only the search timeout is "too broad". The bug is a lost connection that tells the user to narrow a search. */
    @Test
    void aFailureOtherThanTheTimeoutIsRethrownAsItself() {
        var down = new DataAccessResourceFailureException("refused", new SQLException("refused", "08001"));
        assertSame(down, TraceFilters.searchFailure(down));
    }
}
