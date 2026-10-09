// SPDX-License-Identifier: Apache-2.0
package ai.tessary.rca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.classifier.catalog.BuiltInDetector;
import ai.tessary.classifier.catalog.ClassifierMethodCard;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The RCA method files ({@code prompt-craft/rca/methods/}): one per built-in classifier, facts about the instrument
 * only. A method file that lists causes anchors the agent on them, and one that sends it to {@code get_finding} sends
 * it to the finding it must not look up.
 */
class RcaMethodFilesTest {

    private static final List<String> SECTIONS =
            List.of("## What it measures", "## Reading the evidence", "## Measurement quirks", "## In the repo");

    /** {@code get_finding} itself, not {@code get_finding_evidence}. */
    private static final Pattern GET_FINDING = Pattern.compile("get_finding(?!_)");

    /** Every classifier key {@link BuiltInDetector.Kind} declares. */
    static List<String> builtInKeys() {
        List<String> keys = new ArrayList<>();
        for (Field f : BuiltInDetector.Kind.class.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) && Modifier.isPublic(m) && f.getType() == String.class) {
                try {
                    keys.add((String) f.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        assertFalse(keys.isEmpty(), "no classifier keys found");
        return keys;
    }

    @Test
    void everyClassifierWithAMethodCardHasAnRcaMethodFile() {
        for (String key : builtInKeys()) {
            if (ClassifierMethodCard.forClassifier(key) == null) {
                assertNull(AgenticRcaEngine.method(key), key + " has no method card, so it gets no method file");
            } else {
                assertNotNull(AgenticRcaEngine.method(key), key + " has a method card but no RCA method file");
            }
        }
        assertNull(AgenticRcaEngine.method(null), "a user classifier has no key and no method file");
    }

    /** The drift pair measures the same way and shares one file. */
    @Test
    void theDriftClassifiersShareOneFile() {
        assertEquals(
                AgenticRcaEngine.method(BuiltInDetector.Kind.DURATION_DRIFT),
                AgenticRcaEngine.method(BuiltInDetector.Kind.COST_DRIFT));
    }

    @Test
    void aMethodFileHoldsInstrumentFactsOnly() {
        for (String key : builtInKeys()) {
            String method = AgenticRcaEngine.method(key);
            if (method == null) continue;

            assertFalse(
                    GET_FINDING.matcher(method).find(),
                    key + ": numbers are in evidence.md; get_finding is the finding the agent must not look up");
            assertTrue(method.contains("evidence.md"), key + ": say where the numbers are");
            List<String> headings =
                    method.lines().filter(l -> l.startsWith("#")).skip(1).toList();
            assertEquals(SECTIONS, headings, key + ": only the four instrument sections, no cause list");
            for (String heading : method.lines().filter(l -> l.startsWith("#")).toList()) {
                assertFalse(
                        heading.toLowerCase(Locale.ROOT).contains("cause"),
                        key + ": a heading names causes: " + heading);
            }
        }
    }

    /** Groundedness sentences reach the agent over MCP, so the method file has to name where. */
    @Test
    void theGroundednessMethodNamesItsFlaggedSentences() {
        String method = AgenticRcaEngine.method(BuiltInDetector.Kind.GROUNDEDNESS);

        assertNotNull(method);
        assertTrue(method.contains("flaggedSentences"), method);
        assertTrue(method.contains("get_finding_evidence"), method);
    }
}
