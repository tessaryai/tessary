// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static ai.tessary.ingest.GenAiAttributes.PROMPT_NAME;
import static ai.tessary.ingest.GenAiAttributes.PROMPT_VERSION;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the managed prompt a span was produced from: {@code tessary.prompt.name} and {@code tessary.prompt.version}.
 * This reads attributes only: ingest never looks a prompt up, and the producer's text is stored as sent.
 */
public final class PromptRefResolver {

    /** Longer than this and the link is dropped, the span kept: ix_span_prompt is a btree on producer text. */
    public static final int MAX_CHARS = 512;

    private PromptRefResolver() {}

    /** @param version the producer's string, often a number and sometimes a label such as {@code prod} */
    public record PromptRef(String name, @Nullable String version) {}

    /** @return null when the span names no prompt, or the name is too long to index */
    public static @Nullable PromptRef resolve(@Nullable Map<String, ? extends @Nullable Object> attributes) {
        if (attributes == null) {
            return null;
        }
        String name = trimmed(attributes.get(PROMPT_NAME));
        if (name == null || name.length() > MAX_CHARS) {
            return null;
        }
        String version = trimmed(attributes.get(PROMPT_VERSION));
        return new PromptRef(name, version == null || version.length() > MAX_CHARS ? null : version);
    }

    private static @Nullable String trimmed(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = trimEdges(value.toString());
        return text.isEmpty() ? null : text;
    }

    /**
     * Drops whitespace and space characters at either edge, the same rule a stored prompt name is trimmed by
     * ({@code promptlib/PromptNames.lookupName} delegates here), so a span and the prompt it names compare equal.
     */
    public static String trimEdges(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) start++;
        while (end > start && isSpace(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }
}
