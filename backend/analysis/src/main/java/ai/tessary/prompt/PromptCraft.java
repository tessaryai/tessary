// SPDX-License-Identifier: Apache-2.0
package ai.tessary.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.io.ClassPathResource;

/**
 * Reads prompt prose from {@code prompt-craft/<purpose>/<file>} on the classpath.
 *
 * <p><b>Why prose does not live in Java.</b> These strings are sent to a model. As text blocks they
 * sat in the middle of engine classes, where they were invisible to review (a prompt change and a
 * control-flow change look the same in a diff), impossible to read as prose, and subject to
 * incidental-whitespace rules that make "just reindent it" a semantic change. As markdown they are
 * diffable, reviewable by someone who does not read Java, and pinned byte-for-byte by
 * {@code PromptResourceParityTest}.
 *
 * <p><b>What stays in Java.</b> Only the prose moves. Which paragraph applies to a given finding is
 * logic, and logic belongs in code — the engines still choose and concatenate. The rule of thumb: if
 * a human would edit it to change what the model reads, it is prose; if a human would edit it to
 * change when the model reads it, it is code.
 *
 * <p><b>Static on purpose.</b> The prompt builders are static and pure so tests can call them
 * without a Spring context ({@code AgenticRcaPromptTest} does exactly that), so their inputs have to
 * be reachable statically too. {@code CraftLibrary} delegates here rather than duplicating the read,
 * which keeps one home for how a prompt resource is found and cached.
 *
 * <p>Resources resolve across the whole classpath, so each module ships its own prompts:
 * {@code analysis} owns {@code prompt-craft/triage/} without {@code evaluation} knowing it exists.
 */
public final class PromptCraft {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptCraft() {}

    /**
     * One prompt file, verbatim.
     *
     * <p>Verbatim is the contract. Do not trim, normalise line endings, or tidy a blank line: a
     * whitespace change here is a prompt change, and the parity test is the only thing that notices.
     */
    public static String text(String purpose, String file) {
        return CACHE.computeIfAbsent(purpose + "/" + file, key -> {
            try (InputStream in = new ClassPathResource("prompt-craft/" + key).getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("missing prompt resource: prompt-craft/" + key, e);
            }
        });
    }

    /** {@link #text(String, String)} with {@code {{key}}} placeholders substituted. */
    public static String text(String purpose, String file, Map<String, String> vars) {
        String out = text(purpose, file);
        for (Map.Entry<String, String> v : vars.entrySet()) {
            out = out.replace("{{" + v.getKey() + "}}", v.getValue());
        }
        return out;
    }
}
