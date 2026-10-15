// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One {@code invariant_coverage} row from pipeline.yaml: which call sites enforce an implicit invariant and which
 * likely don't, the high-signal input for failure-mode hypotheses. Mirrors the synthesizer's output exactly.
 */
public record InvariantCoverage(
        String invariant,
        @JsonProperty("enforced_in") List<String> enforcedIn,
        @JsonProperty("likely_gap_in") List<String> likelyGapIn) {
    public InvariantCoverage {
        if (enforcedIn == null) enforcedIn = List.of();
        if (likelyGapIn == null) likelyGapIn = List.of();
    }
}
