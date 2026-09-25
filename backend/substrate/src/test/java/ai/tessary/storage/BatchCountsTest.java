// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Statement;
import org.junit.jupiter.api.Test;

class BatchCountsTest {

    /**
     * A driver that rewrote the batch into one statement reports no per-row count, which would hide the
     * last-write-wins guard's verdict; the write must fail loudly rather than proceed on a count it lacks.
     */
    @Test
    void aBatchWithoutPerRowCountsIsRefused() {
        assertDoesNotThrow(() -> BatchCounts.requireReal(new int[] {1, 0, 1}));
        assertThrows(
                IllegalStateException.class, () -> BatchCounts.requireReal(new int[] {1, Statement.SUCCESS_NO_INFO}));
    }
}
