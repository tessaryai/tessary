// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One failure mode (evals plugin schema v0.3). {@code layer} is A (mechanical), B (judgmental), C (adversarial) or null
 * for chain failures. Empty {@code packIds} means baseline. {@code graderDeferred} means grading was intentionally
 * postponed: {@code graderId} is null and no grader file exists, which is distinct from a missing grader.
 */
public record FailureMode(
        String id,
        String name,
        String description,
        @Schema(allowableValues = {"low", "medium", "high"}) String severity,

        @Schema(allowableValues = {"single_call", "chain", "trace"})
        String scope,

        @JsonProperty("call_site_id") @Nullable String callSiteId,
        @JsonProperty("chain_id") @Nullable String chainId,
        @Nullable @Schema(allowableValues = {"A", "B", "C"}) String layer,
        @JsonProperty("pack_ids") List<String> packIds,
        @JsonProperty("compliance_tags") List<String> complianceTags,
        @JsonProperty("taxonomy_node_id") @Nullable String taxonomyNodeId,
        @JsonProperty("grader_deferred") Boolean graderDeferred,
        @JsonProperty("grader_id") @Nullable String graderId) {
    public FailureMode {
        if (scope == null) scope = "single_call";
        if (severity == null) severity = "medium";
        if (packIds == null) packIds = List.of();
        if (complianceTags == null) complianceTags = List.of();
        if (graderDeferred == null) graderDeferred = false;
    }
}
