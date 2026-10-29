// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.open.errors.QueryError;
import ai.tessary.open.errors.TessaryException;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class TraceFiltersTest {

    /** Only the search timeout is "too broad". The bug is a lost connection that tells the user to narrow a search. */
    @Test
    void aFailureOtherThanTheTimeoutIsRethrownAsItself() {
        var down = new DataAccessResourceFailureException("refused", new SQLException("refused", "08001"));
        assertSame(down, TraceFilters.searchFailure(down));
    }

    /**
     * "any" means flagged by anything, so beside an id it would either drop the id or turn all-of into any-of. The bug
     * is a silent pick of one meaning.
     */
    @Test
    void anyBesideAClassifierIdIsRefused() {
        var e = assertThrows(TessaryException.class, () -> TraceFilters.detectedBy(List.of("any", "01JCLASSIFIER")));
        assertEquals(QueryError.INVALID_DETECTED_BY, e.error());
    }

    /** Each pick adds a semi-join; the bug is an unbounded list that grows the query without limit. */
    @Test
    void moreThanTenDistinctIdsAreRefusedAndTenPass() {
        List<String> ten = IntStream.range(0, 10).mapToObj(i -> "c" + i).toList();
        assertEquals(ten, TraceFilters.detectedBy(ten));
        var e = assertThrows(
                TessaryException.class,
                () -> TraceFilters.detectedBy(
                        IntStream.range(0, 11).mapToObj(i -> "c" + i).toList()));
        assertEquals(QueryError.INVALID_DETECTED_BY, e.error());
    }

    /** A link written {@code a,a,} is one pick. The bug is a blank pick that matches nothing and empties the list. */
    @Test
    void blanksAndRepeatsCollapseToTheDistinctPicks() {
        assertEquals(List.of("a", "b"), TraceFilters.detectedBy(List.of("a", " ", "a", "b")));
        assertEquals(List.of(), TraceFilters.detectedBy(null));
    }
}
