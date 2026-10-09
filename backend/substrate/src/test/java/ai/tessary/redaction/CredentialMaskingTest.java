// SPDX-License-Identifier: Apache-2.0
package ai.tessary.redaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

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
}
