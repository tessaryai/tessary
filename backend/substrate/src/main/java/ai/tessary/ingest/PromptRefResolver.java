// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static ai.tessary.ingest.GenAiAttributes.OBSERVATION_PROMPT_NAME_DIALECT;
import static ai.tessary.ingest.GenAiAttributes.OBSERVATION_PROMPT_VERSION_DIALECT;
import static ai.tessary.ingest.GenAiAttributes.PROMPT_NAME;
import static ai.tessary.ingest.GenAiAttributes.PROMPT_VERSION;
import static ai.tessary.ingest.GenAiAttributes.SHORT_PROMPT_NAME_DIALECT;
import static ai.tessary.ingest.GenAiAttributes.SHORT_PROMPT_VERSION_DIALECT;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the managed prompt a span was produced from: {@code tessary.prompt.name}/{@code .version}, then the two
 * third-party prompt dialects in that order. The version is read only from the dialect that named the prompt, so a span is never
 * linked to a version of a prompt it did not use. No lookup against the prompt tables: the link is producer text.
 */
public final class PromptRefResolver {

    /** Longer than this and the link is dropped, the span kept: ix_span_prompt is a btree on producer text. */
    static final int MAX_CHARS = 512;

    private static final String[][] DIALECTS = {
        {PROMPT_NAME, PROMPT_VERSION},
        {OBSERVATION_PROMPT_NAME_DIALECT, OBSERVATION_PROMPT_VERSION_DIALECT},
        {SHORT_PROMPT_NAME_DIALECT, SHORT_PROMPT_VERSION_DIALECT}
    };

    private PromptRefResolver() {}

    /** @param version the producer's string, often a number and sometimes a label such as {@code prod} */
    public record PromptRef(String name, @Nullable String version) {}

    /** @return null when no dialect names a prompt, or the name is too long to index */
    public static @Nullable PromptRef resolve(@Nullable Map<String, ? extends @Nullable Object> attributes) {
        if (attributes == null) {
            return null;
        }
        for (String[] dialect : DIALECTS) {
            String name = trimmed(attributes.get(dialect[0]));
            if (name == null) {
                continue;
            }
            if (name.length() > MAX_CHARS) {
                return null;
            }
            String version = trimmed(attributes.get(dialect[1]));
            return new PromptRef(name, version == null || version.length() > MAX_CHARS ? null : version);
        }
        return null;
    }

    private static @Nullable String trimmed(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
