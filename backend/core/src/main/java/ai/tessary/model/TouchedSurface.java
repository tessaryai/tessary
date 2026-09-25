// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import java.util.Locale;
import java.util.Optional;

/**
 * The diff-classification taxonomy: which kind of LLM-product surface a code
 * change touches. The wire names are the machine-readable contract consumed
 * by risk routing and the change-history risk model; treat renames as breaking.
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

    /** The machine-readable name used in the stored rows. */
    public String wire() {
        return wire;
    }

    /** Case-insensitive parse of a wire name; empty for unknown values (forward-compatible reads). */
    public static Optional<TouchedSurface> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String norm = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (TouchedSurface s : values()) {
            if (s.wire.equals(norm)) return Optional.of(s);
        }
        return Optional.empty();
    }
}
