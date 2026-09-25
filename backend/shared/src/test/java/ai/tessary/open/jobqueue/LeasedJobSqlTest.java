// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.jobqueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LeasedJobSqlTest {

    @Test
    void everyDeadLetterVariantCarriesThePreviousError() {
        // The exhausted phrase alone erased why the attempts failed; each variant must keep it.
        String carried = LeasedJobSql.exhaustedLastError("hung");
        assertEquals(
                "'exhausted: ' || attempts || ' attempts (hung)' || COALESCE('; last: ' || left(last_error, 500), '')",
                carried);
        assertTrue(LeasedJobSql.failExhaustedOfKind("job", "hung", "dead").contains(carried));
    }
}
