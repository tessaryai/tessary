// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Which kind of LLM-product surface a code change touches. Wire names are consumed by risk routing; renames are
 * breaking.
 */
public enum TouchedSurface {
    PROMPT("prompt"),
    RETRIEVAL_RAG("retrieval_rag"),
    TOOL_DEFINITION("tool_definition"),
    AGENT_LOOP("agent_loop"),
    MODEL_PARAMS("model_params"),
    DEPENDENCY("dependency");

    private final String wire;

    TouchedSurface(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** Empty for unknown values, for forward-compatible reads. */
    public static Optional<TouchedSurface> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String norm = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (TouchedSurface s : values()) {
            if (s.wire.equals(norm)) return Optional.of(s);
        }
        return Optional.empty();
    }
}
