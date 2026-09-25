// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** {@link ErrorCode#render}: arguments that do not fit the template fall back to the template itself. */
class ErrorCodeTest {

    /**
     * The bug: a caller passing a string where the template says {@code %d} throws from inside the error
     * path, so the client gets a 500 in place of the error it was meant to see.
     */
    @Test
    void argsThatDoNotFitTheTemplateRenderTheTemplate() {
        assertEquals(
                "Quota '%s' exceeded: %d of %d used for this period",
                CapabilityError.QUOTA_EXCEEDED.render("traces", "ten", "five"));
    }
}
