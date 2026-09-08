// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One failure mode (evals plugin schema v0.3).
 *
 * <p>{@code layer} is {@code "A" | "B" | "C" | null}: A = mechanical /
 * structural, B = judgmental, C = adversarial / operational (new in v0.2).
 * Chain failures use {@code null}.
 *
 * <p>{@code packIds} is the set of packs that contributed this failure (one
 * failure can belong to N packs — set-valued). Empty/[] = baseline.
 *
 * <p>{@code complianceTags} is the union of contributing packs' control IDs,
 * narrowed by interview answers. Free-form strings (e.g.
 * {@code EU-AI-Act.Art-13}, {@code HIPAA-164.502}).
 *
 * <p>{@code graderDeferred} (evals plugin v0.7): true when the authoring sweep chose
 * not to synthesise a grader for this failure yet (medium/low severity). A
 * deferred failure carries {@code graderId == null} and has no grader file — it
 * is recorded but not gradeable until a later authoring pass (the observer, or a
 * human editing the bundle) clears it. Distinguishes "intentionally not graded
 * yet" from "grader missing (bug)".
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
