// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static ai.tessary.ingest.GenAiAttributes.PROMPT_NAME;
import static ai.tessary.ingest.GenAiAttributes.PROMPT_VERSION;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the managed prompt a span was produced from: {@code tessary.prompt.name} and {@code tessary.prompt.version}.
 * This reads attributes only; {@link PromptLinkIds} resolves the name to a prompt id once per batch, and the producer's
 * text is kept either way.
 */
public final class PromptRefResolver {

    /** Longer than this and the link is dropped, the span kept: ix_span_prompt is a btree on producer text. */
    static final int MAX_CHARS = 512;

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
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
