// SPDX-License-Identifier: Apache-2.0
package ai.tessary.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins every prompt file in {@code prompt-craft/} to its exact bytes. A tidied blank line changes what the model reads
 * and nothing else notices, so each pinned text must reproduce its golden in {@code src/test/resources/prompt-golden/}
 * byte for byte. A text the engine loads into a constant is pinned through that constant; one it loads per run (an
 * RCA method file) is pinned through its resource. {@code -Dprompt.golden.capture=true} rewrites the goldens.
 */
class PromptResourceParityTest {

    private static final Path GOLDEN = Path.of("src/test/resources/prompt-golden");

    /** Golden name -> the text it pins. */
    private static Map<String, Supplier<String>> pinned() {
        Map<String, Supplier<String>> m = new LinkedHashMap<>();
        constant(m, ai.tessary.classifier.finding.BehaviorTriageEngine.class, "SYSTEM_PROMPT");
        constant(m, ai.tessary.rca.AgenticRcaEngine.class, "PROMPT");
        constant(m, ai.tessary.rca.AgenticRcaEngine.class, "REPO_PRESENT");
        constant(m, ai.tessary.rca.AgenticRcaEngine.class, "REPO_ABSENT");
        constant(m, ai.tessary.rca.AgenticRcaEngine.class, "BASELINE_PRESENT");
        constant(m, ai.tessary.rca.AgenticRcaEngine.class, "TOOLS");
        constant(m, ai.tessary.rca.AgenticRcaEngine.class, "JSON_SCHEMA");
        for (String method : List.of(
                "tool_error", "metric_drift", "secret_leak", "malformed_output", "frustration", "groundedness")) {
            m.put("rca.methods." + method, () -> PromptCraft.text("rca", "methods/" + method + ".md"));
        }
        return m;
    }

    private static void constant(Map<String, Supplier<String>> m, Class<?> owner, String name) {
        m.put(owner.getSimpleName() + "." + name, () -> {
            try {
                Field f = owner.getDeclaredField(name);
                f.setAccessible(true);
                return (String) f.get(null);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(owner.getSimpleName() + "." + name + " is not a pinnable constant", e);
            }
        });
    }

    /**
     * The pin list is hand-written, so this counts the shipped prompt files and requires the list to cover them.
     * {@code response_schema.json} is data the prompt carries, pinned like the prose.
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
    void the_prompt_files_are_byte_identical_to_their_goldens() throws Exception {
        boolean capture = Boolean.getBoolean("prompt.golden.capture");
        if (capture) Files.createDirectories(GOLDEN);
        for (Map.Entry<String, Supplier<String>> e : pinned().entrySet()) {
            String actual = e.getValue().get();
            Path golden = GOLDEN.resolve(e.getKey() + ".txt");
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

    /** A golden whose text is no longer pinned would read as coverage while pinning nothing. */
    @Test
    void every_golden_belongs_to_a_pinned_text() throws Exception {
        try (var list = Files.list(GOLDEN)) {
            Set<String> goldens = list.map(p -> p.getFileName().toString().replaceFirst("\\.txt$", ""))
                    .collect(Collectors.toCollection(TreeSet::new));
            assertEquals(new TreeSet<>(pinned().keySet()), goldens);
        }
    }
}
