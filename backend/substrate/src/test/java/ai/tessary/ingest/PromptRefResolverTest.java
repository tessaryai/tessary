// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.ingest.PromptRefResolver.PromptRef;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The managed prompt a span came from: the {@code tessary.prompt.*} pair, blanks read as absent, and a link too long
 * for the index dropped without dropping the span.
 */
class PromptRefResolverTest {

    @Test
    void nameAndVersion() {
        // Bug: an OTLP integer version (a Long) is lost or stored as anything other than its decimal string.
        assertEquals(
                new PromptRef("team/a", "3"),
                PromptRefResolver.resolve(
                        Map.of(GenAiAttributes.PROMPT_NAME, "team/a", GenAiAttributes.PROMPT_VERSION, 3L)));
    }

    @Test
    void blankNameIsNull() {
        // Bug: a blank name links the span to a prompt called "  ", or a blank version reads as something other than
        // null.
        assertNull(PromptRefResolver.resolve(Map.of(GenAiAttributes.PROMPT_NAME, "  ")));
        assertNull(PromptRefResolver.resolve(null));
        Map<String, Object> blankVersion = new HashMap<>();
        blankVersion.put(GenAiAttributes.PROMPT_NAME, " p ");
        blankVersion.put(GenAiAttributes.PROMPT_VERSION, " ");
        assertEquals(new PromptRef("p", null), PromptRefResolver.resolve(blankVersion));
    }

    @Test
    void oversizedNameDropsTheLinkNotTheSpan() {
        // Bug: a producer string longer than the btree index allows is written, or a long version also drops the name.
        String long513 = "a".repeat(513);
        assertNull(PromptRefResolver.resolve(Map.of(GenAiAttributes.PROMPT_NAME, long513)));
        assertEquals(
                new PromptRef("p", null),
                PromptRefResolver.resolve(
                        Map.of(GenAiAttributes.PROMPT_NAME, "p", GenAiAttributes.PROMPT_VERSION, long513)));
        String exact512 = "b".repeat(512);
        assertEquals(
                new PromptRef(exact512, exact512),
                PromptRefResolver.resolve(
                        Map.of(GenAiAttributes.PROMPT_NAME, exact512, GenAiAttributes.PROMPT_VERSION, exact512)));
    }

    @Test
    void theNameIsTrimmedExactlyAsPromptNamesAreSoTheyCompareEqual() {
        // Bug: a no-break space at the edge survives on the span while the stored prompt name drops it, so the span's
        // calls never count on that prompt; or a control character is stripped here and kept there.
        assertEquals(
                new PromptRef("summarize", null),
                PromptRefResolver.resolve(Map.of(GenAiAttributes.PROMPT_NAME, "\u00A0summarize\u2003")));
        assertEquals(
                new PromptRef("summarize\u0001", null),
                PromptRefResolver.resolve(Map.of(GenAiAttributes.PROMPT_NAME, "summarize\u0001")));
        assertEquals("summarize", PromptRefResolver.trimEdges("\u00A0summarize\u2003"));
    }
}
