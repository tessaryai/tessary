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
 * Reads prompt prose from {@code prompt-craft/<purpose>/<file>} on the classpath, so prompts are reviewable as prose
 * and pinned by {@code PromptResourceParityTest}. Which paragraph applies stays in Java. Static so prompt builders stay
 * testable without Spring; each module ships its own prompts.
 */
public final class PromptCraft {

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private PromptCraft() {}

    /** Verbatim is the contract: a whitespace change is a prompt change. */
    public static String text(String purpose, String file) {
        return CACHE.computeIfAbsent(purpose + "/" + file, key -> {
            try (InputStream in = new ClassPathResource("prompt-craft/" + key).getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("missing prompt resource: prompt-craft/" + key, e);
            }
        });
    }
}
