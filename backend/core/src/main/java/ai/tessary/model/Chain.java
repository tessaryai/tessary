// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One chain detected across call sites (evals plugin schema v0.3).
 *
 * <p>{@code detectionMethod} is one of {@code trace_confirmed | ensemble |
 * state_mediated | sequential_composition}. {@code ensemble} is new in v0.3:
 * N sibling spans under the same parent with identical normalized prompts
 * (self-consistency / voting). The combined unit is one chain whose
 * {@code callSiteIds} is the same id repeated; the runner grades disagreement
 * across the siblings using {@code ensembleSpanIds}.
 */
public record Chain(
        String id,
        String name,
        @JsonProperty("call_site_ids") List<String> callSiteIds,

        @JsonProperty("detection_method")
        @Schema(allowableValues = {"trace_confirmed", "ensemble", "state_mediated", "sequential_composition"})
        String detectionMethod,

        String confidence,
        String rationale,
        @JsonProperty("ensemble_span_ids") List<String> ensembleSpanIds) {
    public Chain {
        callSiteIds = callSiteIds == null ? List.of() : callSiteIds;
        ensembleSpanIds = ensembleSpanIds == null ? List.of() : ensembleSpanIds;
    }
}
