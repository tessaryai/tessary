// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.obs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** {@link LogContext}: a null value binds nothing, so the caller's own value stays in place. */
class LogContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    /**
     * The bug: a scope opened with a null project id blanks the project id the caller had already bound,
     * so every line inside the scope loses its tenant.
     */
    @Test
    void aNullValueLeavesTheExistingBindingInPlace() {
        MDC.put(LogContext.PROJECT_ID, "p1");

        try (LogContext ignored = LogContext.with(LogContext.PROJECT_ID, null)) {
            assertEquals("p1", MDC.get(LogContext.PROJECT_ID));
        }
        assertEquals("p1", MDC.get(LogContext.PROJECT_ID));
    }
}
