// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the prompt prose that moved out of Java into {@code prompt-craft/} markdown to the exact
 * bytes it had as a Java constant.
 *
 * <p>These strings are sent to a model. A refactor that "tidies" a blank line or a trailing space
 * changes what the model reads, and nothing else in the build would notice — the prompt still
 * compiles, the lane still runs, and the ruling quietly shifts. So the move is verified rather than
 * trusted: the golden files under {@code src/test/resources/prompt-golden/} were captured from the
 * pre-refactor constants by reflection, and this asserts the markdown reproduces them byte for byte.
 *
 * <p>Set {@code -Dprompt.golden.capture=true} to rewrite the goldens. That is for the initial
 * capture only; running it to make a failure go away defeats the test.
 */
class PromptResourceParityTest {

    private static final Path GOLDEN = Path.of("src/test/resources/prompt-golden");

    /** Constant name -> owning class, for everything that moved. */
    private static Map<String, Class<?>> pinned() {
        Map<String, Class<?>> m = new LinkedHashMap<>();
        m.put("triage#SUMMARY_RULE", ai.tessary.evals.classifier.finding.BehaviorTriageEngine.class);
        m.put("triage#PREFLIGHT", ai.tessary.evals.classifier.finding.BehaviorTriageEngine.class);
        m.put("triage#RULES", ai.tessary.evals.classifier.finding.BehaviorTriageEngine.class);
        m.put("triage#MCP_DOOR", ai.tessary.evals.classifier.finding.BehaviorTriageEngine.class);
        m.put("triage#CHECKS_RULE", ai.tessary.evals.classifier.finding.BehaviorTriageEngine.class);
        m.put("triage#CITATION_RULE", ai.tessary.evals.classifier.finding.BehaviorTriageEngine.class);
        m.put("rca#JSON_SCHEMA", ai.tessary.evals.rca.AgenticRcaEngine.class);
        m.put("rca#RULES", ai.tessary.evals.rca.AgenticRcaEngine.class);
        m.put("rca#MCP_DOOR", ai.tessary.evals.rca.AgenticRcaEngine.class);
        return m;
    }

    /**
     * The pin list is hand-written, so a prompt added without an entry would ship unprotected and
     * nothing would say so. This counts the prose files this module ships and requires the list to
     * cover them, which turns "remember to pin it" into a failing build.
     *
     * <p>{@code response_schema.json} is excluded deliberately: it is data the prompt carries, not
     * prose, and it is pinned as a constant like the rest.
     */
    @Test
    void every_prompt_file_this_module_ships_is_pinned() throws Exception {
        Path craft = Path.of("src/main/resources/prompt-craft");
        try (var walk = Files.walk(craft)) {
            long files = walk.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".md") || f.toString().endsWith(".json"))
                    .count();
            assertEquals(
                    files,
                    pinned().size(),
                    "prompt-craft ships " + files + " prompt files but " + pinned().size()
                            + " are pinned — add the new one to pinned() so it cannot change unnoticed");
        }
    }

    @Test
    void the_prose_that_moved_to_markdown_is_byte_identical() throws Exception {
        boolean capture = Boolean.getBoolean("prompt.golden.capture");
        if (capture) Files.createDirectories(GOLDEN);
        for (Map.Entry<String, Class<?>> e : pinned().entrySet()) {
            String constName = e.getKey().substring(e.getKey().indexOf('#') + 1);
            Field f = e.getValue().getDeclaredField(constName);
            f.setAccessible(true);
            String actual = (String) f.get(null);
            Path golden = GOLDEN.resolve(e.getValue().getSimpleName() + "." + constName + ".txt");
            if (capture) {
                Files.writeString(golden, actual, StandardCharsets.UTF_8);
                continue;
            }
            assertEquals(
                    Files.readString(golden, StandardCharsets.UTF_8),
                    actual,
                    e.getKey() + " no longer matches its golden — the prompt text changed");
        }
    }
}
