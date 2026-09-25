// SPDX-License-Identifier: Apache-2.0
package ai.tessary.prompt;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;

class PromptCraftTest {

    /**
     * A prompt file that is not on the classpath fails the build of the prompt, every time it is asked for:
     * an empty string in its place would send the model a prompt with a paragraph silently missing.
     */
    @Test
    void aMissingPromptFileThrowsAndIsNotCachedAsEmpty() {
        assertThrows(UncheckedIOException.class, () -> PromptCraft.text("triage", "no-such-file.md"));
        assertThrows(UncheckedIOException.class, () -> PromptCraft.text("triage", "no-such-file.md"));
    }
}
