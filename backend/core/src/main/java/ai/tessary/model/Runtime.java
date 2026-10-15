// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Pipeline runtime configuration, persisted for judge runners and CI. {@code severityPolicy} maps severity to
 * {@code block | warn | report}, overridable per grader. {@code redactionState} is the worst case across call sites.
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
