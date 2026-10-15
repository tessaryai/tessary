// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One LLM call site (evals plugin schema v0.3). {@code shape} is an open string so new shapes need no model change.
 * Absent {@code invocation} means {@code sdk}, normalized in the constructor. {@code outputSchema} and {@code tools}
 * describe the code, not the traffic; {@code outputSchema} has its own column because the platform writes it too.
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
     * {@code inputSchema} stays a {@link JsonNode} since it is arbitrary. An empty list means "the bundle declares
     * none", which is also what an older bundle produces, not "no tools".
     */
    public record ToolSpec(
            String name,
            @Nullable String description,
            @JsonProperty("input_schema") @Nullable JsonNode inputSchema,
            @Nullable String source) {}

    /**
     * One code-derived proto-mapping rule (contract v8). {@code source} {@code observed} is verified telemetry trusted
     * regardless of {@code confidence}; {@code inferred}, the default, is a guess from code.
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
