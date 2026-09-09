// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One LLM call site (evals plugin schema v0.3).
 *
 * <p>{@code shape} is one of: {@code summarize | extract | rag_answer |
 * classify | draft | route | tool_call | agent_step | conversational_turn |
 * embedding | rerank | guardrail | moderation | ensemble_vote | other}.
 * Held as an open string so future shapes don't require a model change.
 *
 * <p>{@code invocation} (schema v0.9.0) records how the model is reached:
 * {@code sdk | cli_agent | http | sandbox_agent}. Absent on the wire means
 * {@code sdk} (the only value pre-0.9.0), normalized in the constructor.
 *
 * <p>Trace-derived fields (populated only on Path A — traces provided):
 * <ul>
 *   <li>{@code sourceSpans} — representative spans (up to ~10, stratified
 *       across time buckets) that contributed to this call-site identity.
 *   <li>{@code datasetPath} — relative path under {@code evals/} to a
 *       captured-inputs JSONL for replay.
 *   <li>{@code observed} — production stats across the full sample.
 * </ul>
 *
 * <p>Code-tracked facts — {@code outputSchema} (the declared structured-output JSON Schema) and
 * {@code tools} (the declared tool schemas). These describe the call site's CODE rather than its
 * traffic, so the {@code .tessary/} bundle carries them and the platform keeps them in sync as the
 * code changes. {@code outputSchema} also has a platform-side writer (agentic synthesis reads it out
 * of the repo), which is why it persists to its own column rather than riding along in JSON.
 *
 * <p>{@code expectedSpans} (contract v8) — code-derived telemetry nomenclature:
 * the span/trace names the plugin's discovery step observed the instrumentation
 * emit for this call site (OTel {@code start_span}, the enclosing function name,
 * etc.), expressed as proto-mapping rules. Orchestrator-owned (the plugin emits
 * them); empty when discovery found no instrumentation hint.
 */
public record CallSite(
        String id,
        @JsonProperty("use_case") @Nullable String useCase,

        @Schema(allowableValues = {"sdk", "cli_agent", "http", "sandbox_agent"})
        String invocation,

        String provider,
        @Nullable String model,
        @JsonProperty("system_prompt") @Nullable String systemPrompt,
        @JsonProperty("prompt_text") @Nullable String promptText,
        @JsonProperty("surrounding_code") @Nullable String surroundingCode,
        @JsonProperty("file_hint") @Nullable String fileHint,
        @JsonProperty("line_hint") @Nullable Integer lineHint,

        @Nullable
        @Schema(
                allowableValues = {
                    "summarize",
                    "extract",
                    "rag_answer",
                    "classify",
                    "draft",
                    "route",
                    "tool_call",
                    "agent_step",
                    "conversational_turn",
                    "embedding",
                    "rerank",
                    "guardrail",
                    "moderation",
                    "ensemble_vote",
                    "other"
                })
        String shape,

        @JsonProperty("shape_confidence") @Nullable String shapeConfidence,
        @Nullable String intent,
        List<Constraint> constraints,
        @JsonProperty("sample_count") @Nullable Integer sampleCount,
        @JsonProperty("source_spans") List<SourceSpan> sourceSpans,
        @JsonProperty("dataset_path") @Nullable String datasetPath,
        @Nullable Observed observed,
        @JsonProperty("expected_spans") List<ExpectedSpan> expectedSpans,
        @JsonProperty("output_schema") @Nullable JsonNode outputSchema,
        List<ToolSpec> tools) {
    public CallSite {
        if (provider == null) provider = "unknown";
        if (invocation == null) invocation = "sdk";
        constraints = constraints == null ? List.of() : constraints;
        sourceSpans = sourceSpans == null ? List.of() : sourceSpans;
        expectedSpans = expectedSpans == null ? List.of() : expectedSpans;
        tools = tools == null ? List.of() : tools;
    }

    /**
     * One tool the call site declares to the model, as the bundle carries it.
     *
     * <p>{@code inputSchema} is the tool's argument JSON Schema — arbitrary by nature, so it stays a
     * {@link JsonNode} rather than being modelled. {@code source} is a {@code file:line} pointer back
     * to the declaration, mirroring {@code fileHint}/{@code lineHint} on the call site itself.
     *
     * <p>An empty list is NOT "this call site has no tools" — it is "the bundle declares none", which
     * is also what a bundle written before this field existed produces. Only a consumer that can tell
     * the two apart should treat absence as a fact.
     */
    public record ToolSpec(
            String name,
            @Nullable String description,
            @JsonProperty("input_schema") @Nullable JsonNode inputSchema,
            @Nullable String source) {}

    /**
     * One code-derived proto-mapping rule (contract v8). {@code matchField} is one
     * of {@code name | model | trace_id | metadata.<key>}; {@code matchPattern} is an exact string or glob
     * ({@code * } / {@code ?}); {@code kind} is {@code span | trace}; {@code
     * confidence} is {@code high | medium | low}.
     *
     * <p>{@code source} (contract v9) is {@code observed | inferred} (default {@code inferred}).
     * {@code observed} means the rule was read from real OTel/trace telemetry and is a verified
     * fact the platform trusts unconditionally (its {@code confidence} is moot); {@code inferred}
     * is the v8 best-effort guess from the call site's code, where {@code confidence} still ranks.
     */
    public record ExpectedSpan(
            @JsonProperty("match_field") String matchField,
            @JsonProperty("match_pattern") String matchPattern,
            String kind,
            String confidence,
            String source) {
        public ExpectedSpan {
            if (source == null) source = "inferred";
        }
    }
}
