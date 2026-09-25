// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Masking every credential in a block of text for display, on a surface that must never hand a raw one to a
 * browser or an MCP client. The key below is FAKE, the same one {@link GitleaksCorpusTest} uses.
 */
class CredentialMaskingTest {

    private static final String AWS = "AKIA" + "QYLPMN5HHHFPZAM2";

    @Test
    void mask_replacesEachCredentialWithItsMaskedFormAndKeepsTheProseAroundIt() {
        assertEquals(
                "first AKIA…ZAM2, then AKIA…ZAM2 again",
                CredentialMasking.mask("first " + AWS + ", then " + AWS + " again"));
    }

    /** Text with nothing to mask comes back as the same reference: the display path allocates nothing. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"no credentials in here"})
    void mask_leavesTextWithoutCredentialsAlone(String text) {
        assertSame(text, CredentialMasking.mask(text));
    }
}
