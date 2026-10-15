// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One chain across call sites (evals plugin v0.3). {@code ensemble} is N sibling spans with identical normalized
 * prompts: one chain repeating the same call-site id, graded on disagreement across {@code ensembleSpanIds}.
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
