// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ErrorSignatureTest {

    /**
     * The bug: a long message's signature is unbounded, or its cut lands on a space and keeps it, so two
     * failures of one pattern that differ only past the cap group as two signatures.
     */
    @Test
    void aLongMessageIsCutAt120CharsWithoutATrailingSpace() {
        String message = "timeout ".repeat(30);

        assertEquals("timeout ".repeat(15).trim() + "…", ErrorSignature.signature(message));
    }
}
