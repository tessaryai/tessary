// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.model;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The diff-classification taxonomy: which kind of LLM-product surface a code
 * change touches. This enum is the single source of truth — the agent's JSON schema
 * ({@link AgentVerdict#JSON_SCHEMA}) and the agent prompt both derive their surface lists from
 * here, so the three can never drift. The wire names are the machine-readable contract consumed
 * by risk routing and the change-history risk model; treat renames as breaking.
 */
public enum TouchedSurface {
    PROMPT("prompt", "a prompt — system/user prompt text, prompt template, or a file a prompt is loaded from"),
    RETRIEVAL_RAG(
            "retrieval_rag",
            "retrieval / RAG — embedding, chunking, indexing, vector search, ranking, or retrieved-context assembly"),
    TOOL_DEFINITION(
            "tool_definition",
            "a tool definition — tool/function names, schemas, descriptions, or the tool implementations an agent"
                    + " calls"),
    AGENT_LOOP(
            "agent_loop",
            "agent loop / control flow — orchestration, planning, routing, retries, stop conditions, sub-agent"
                    + " dispatch, or conversation/state management"),
    MODEL_PARAMS(
            "model_params",
            "model / params — model id or provider selection, temperature/top_p/max_tokens, structured-output or"
                    + " response-format settings"),
    DEPENDENCY(
            "dependency",
            "a dependency — LLM/agent-relevant package or SDK version changes (lockfiles, manifests), or vendored"
                    + " library updates");

    private final String wire;
    private final String description;

    TouchedSurface(String wire, String description) {
        this.wire = wire;
        this.description = description;
    }

    /** The machine-readable name used in the JSON schema, the agent output, and the stored rows. */
    public String wire() {
        return wire;
    }

    /** One-line definition of the surface, embedded verbatim in the agent prompt. */
    public String description() {
        return description;
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

    /** The wire names as a JSON enum list (e.g. {@code "prompt", "retrieval_rag", …}) for the schema. */
    public static String wireEnumJson() {
        return Stream.of(values()).map(s -> '"' + s.wire + '"').collect(Collectors.joining(", "));
    }
}
