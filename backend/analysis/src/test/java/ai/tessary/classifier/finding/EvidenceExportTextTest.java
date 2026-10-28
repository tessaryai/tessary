// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The export's text cut: masked before it is cut, every attribute string included. The key is FAKE. */
class EvidenceExportTextTest {

    private static final String AWS = "AKIA" + "QYLPMN5HHHFPZAM2";
    private static final String AWS_MASKED = "AKIA…ZAM2";

    /** Catches a cut before the mask: the first ten characters of the key would survive as {@code AKIAQYLPMN}. */
    @Test
    void maskThenCut_masksBeforeItCuts() {
        String filler = "x".repeat(3_989);
        String text = filler + " " + AWS + " " + "y".repeat(100);

        String out = EvidenceExportText.maskThenCut(text, 4_000);

        // Masked, the key is 9 characters, so the first 4,000 end on the space after it.
        assertEquals(filler + " " + AWS_MASKED + " " + EvidenceExportText.CUT_MARK, out);
        assertFalse(String.valueOf(out).contains("AKIAQYLPMN"), "the raw key reached the export");
    }

    /** Catches a nested value walking past the mask. */
    @Test
    void attributes_masksEveryStringAndKeepsScalars() {
        String json = "{\"deploy\":\"v1\",\"n\":3,\"ok\":true,\"nested\":{\"k\":\"key " + AWS + " here\"},"
                + "\"list\":[\"x\",5,\"" + AWS + " end\"]}";

        Map<String, Object> out = EvidenceExportText.attributes(json, 4_000);

        assertNotNull(out);
        assertEquals(List.of("deploy", "n", "ok", "nested", "list"), List.copyOf(out.keySet()));
        assertEquals("v1", out.get("deploy"));
        assertEquals(3, out.get("n"));
        assertEquals(true, out.get("ok"));
        assertEquals(Map.of("k", "key " + AWS_MASKED + " here"), out.get("nested"));
        assertEquals(List.of("x", 5, AWS_MASKED + " end"), out.get("list"));
    }

    /** Catches one huge attribute defeating the page budget, or being dropped instead of cut. */
    @Test
    void attributes_cutsALongValueAndMarksIt() {
        String json = "{\"prompt\":\"" + "z".repeat(5_000) + "\"}";

        Map<String, Object> out = EvidenceExportText.attributes(json, 4_000);

        assertNotNull(out);
        assertEquals("z".repeat(4_000) + EvidenceExportText.CUT_MARK, out.get("prompt"));
    }
}
