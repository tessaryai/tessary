// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One row of the top-level {@code invariant_coverage} block in pipeline.yaml
 * (evals plugin output_format.md). For each implicit invariant, lists which
 * call sites appear to enforce it and which likely don't — the "don't" list
 * is the high-signal input for Layer B failure-mode hypothesis.
 *
 * <p>Distinct from {@link ImplicitInvariant} (which is the invariant itself)
 * and from the platform's per-invariant coverage stats. This record mirrors
 * exactly what the synthesizer emits.
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
