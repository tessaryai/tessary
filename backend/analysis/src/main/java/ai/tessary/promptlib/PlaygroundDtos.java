// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import ai.tessary.llm.ModelProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The prompt playground's wire shapes, snake_case. {@code config} is a {@code Map}, never a Jackson 2
 * {@code JsonNode}, since the HTTP converter is Jackson 3; a non-object {@code config} fails binding.
 */
public final class PlaygroundDtos {

    private PlaygroundDtos() {}

    public record Message(
            @NotBlank @Pattern(regexp = "system|user|assistant")
            String role,

            @NotNull @Size(max = 200_000) String content) {}

    /** The version the run was compiled from; its id is the ledger subject. */
    public record PromptRef(
            @JsonProperty("prompt_id") @NotBlank String promptId,
            @Positive int version) {}

    public record PlaygroundRunRequest(
            @NotNull ModelProvider provider,
            @NotBlank @Size(max = 200) String model,
            @NotEmpty @Size(max = 64) List<@Valid Message> messages,
            @Nullable Map<String, Object> config,
            @JsonProperty("prompt_ref") @Nullable @Valid PromptRef promptRef) {}

    /** {@code price_source} is {@code book}, {@code openrouter} or {@code unpriced}. */
    public record PlaygroundRunView(
            String content,
            @JsonProperty("finish_reason") @Nullable String finishReason,
            @JsonProperty("input_tokens") @Nullable Integer inputTokens,
            @JsonProperty("output_tokens") @Nullable Integer outputTokens,
            @JsonProperty("cache_read_tokens") @Nullable Integer cacheReadTokens,
            @JsonProperty("cache_write_tokens") @Nullable Integer cacheWriteTokens,
            @JsonProperty("latency_ms") long latencyMs,
            @JsonProperty("cost_usd") @Nullable BigDecimal costUsd,
            @JsonProperty("price_source") String priceSource,
            ModelProvider provider,
            String model) {}
}
