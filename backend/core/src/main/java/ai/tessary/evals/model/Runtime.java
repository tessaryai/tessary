// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Pipeline-level runtime configuration (evals plugin v0.2+). Consumed by judge
 * runners and CI integrations to gate behavior; the synthesis pipeline itself
 * just persists it.
 *
 * <p>{@code severityPolicy} maps severity → {@code block | warn | report}.
 * Per-grader {@code block_on_fail} on a {@link Grader} overrides this default.
 *
 * <p>{@code redactionState} is the worst case across all call sites' observed
 * stats — {@code redacted} if any site is fully redacted, else {@code partial}
 * if any is partial, else {@code none}.
 */
public record Runtime(
        @JsonProperty("judge_model") @Nullable String judgeModel,
        @JsonProperty("judge_temperature") @Nullable Double judgeTemperature,
        @JsonProperty("max_concurrency") @Nullable Integer maxConcurrency,
        @JsonProperty("budget_usd_per_run") @Nullable Double budgetUsdPerRun,
        @JsonProperty("severity_policy") Map<String, String> severityPolicy,

        @JsonProperty("redaction_state") @Schema(allowableValues = {"none", "partial", "redacted", "unknown"}) @Nullable
        String redactionState) {
    public Runtime {
        if (severityPolicy == null) severityPolicy = Map.of();
    }
}
