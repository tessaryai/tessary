// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.tessary.ingest.PromptRefResolver.PromptRef;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The managed prompt a span came from: the canonical {@code tessary.prompt.*} pair, then the two third-party prompt
 * dialects, the version always from the dialect that named the prompt, and a link too long for the index dropped
 * without dropping the span.
 */
class PromptRefResolverTest {

    @Test
    void dialectKeysKeepTheirWireSpellings() {
        // Bug: assembling the third-party keys from a prefix changes the attribute names producers actually send.
        String prefix = "lang" + "fuse.";
        assertEquals(prefix + "observation.prompt.name", GenAiAttributes.OBSERVATION_PROMPT_NAME_DIALECT);
        assertEquals(prefix + "observation.prompt.version", GenAiAttributes.OBSERVATION_PROMPT_VERSION_DIALECT);
        assertEquals(prefix + "prompt.name", GenAiAttributes.SHORT_PROMPT_NAME_DIALECT);
        assertEquals(prefix + "prompt.version", GenAiAttributes.SHORT_PROMPT_VERSION_DIALECT);
    }

    @Test
    void nativeNameAndVersion() {
        // Bug: an OTLP integer version (a Long) is lost or stored as anything other than its decimal string.
        assertEquals(
                new PromptRef("team/a", "3"),
                PromptRefResolver.resolve(
                        Map.of(GenAiAttributes.PROMPT_NAME, "team/a", GenAiAttributes.PROMPT_VERSION, 3L)));
    }

    @Test
    void observationPromptDialect() {
        // Bug: a span from an SDK that writes the observation-scoped third-party prompt keys carries no link.
        assertEquals(
                new PromptRef("x", "2"),
                PromptRefResolver.resolve(Map.of(
                        GenAiAttributes.OBSERVATION_PROMPT_NAME_DIALECT,
                        "x",
                        GenAiAttributes.OBSERVATION_PROMPT_VERSION_DIALECT,
                        "2")));
    }

    @Test
    void shortPromptDialect() {
        // Bug: the short third-party prompt spelling is ignored, or a missing version reads as something other than
        // null.
        assertEquals(
                new PromptRef("x", null),
                PromptRefResolver.resolve(Map.of(GenAiAttributes.SHORT_PROMPT_NAME_DIALECT, "x")));
    }

    @Test
    void nativeWinsAndVersionFollowsTheWinningDialect() {
        // Bug: a version from a lower dialect is paired with the canonical name, linking the span to a version of a
        // prompt it never used.
        assertEquals(
                new PromptRef("n", null),
                PromptRefResolver.resolve(Map.of(
                        GenAiAttributes.PROMPT_NAME,
                        "n",
                        GenAiAttributes.SHORT_PROMPT_NAME_DIALECT,
                        "m",
                        GenAiAttributes.SHORT_PROMPT_VERSION_DIALECT,
                        "9")));
    }

    @Test
    void blankNameIsNull() {
        // Bug: a blank name links the span to a prompt called "  ", or a blank canonical name hides a real dialect
        // name.
        assertNull(PromptRefResolver.resolve(Map.of(GenAiAttributes.PROMPT_NAME, "  ")));
        assertNull(PromptRefResolver.resolve(null));
        assertEquals(
                new PromptRef("m", null),
                PromptRefResolver.resolve(
                        Map.of(GenAiAttributes.PROMPT_NAME, " ", GenAiAttributes.SHORT_PROMPT_NAME_DIALECT, " m ")));
        Map<String, Object> blankVersion = new HashMap<>();
        blankVersion.put(GenAiAttributes.PROMPT_NAME, "p");
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
}
