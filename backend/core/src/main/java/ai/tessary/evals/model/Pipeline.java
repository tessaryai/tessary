// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Top-level eval-pipeline record. Mirrors {@code pipeline.yaml} as written by
 * the evals plugin orchestrator at step 7 (schema v0.3.0).
 *
 * <p>Field order is significant for YAML output — matches the canonical
 * ordering documented in the evals plugin's output_format.md.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Pipeline(
        String version,
        @Nullable @JsonProperty("product_hint") String productHint,
        List<Pack> packs,
        @Nullable @JsonProperty("product_profile") ProductProfile productProfile,
        @JsonProperty("implicit_invariants") List<ImplicitInvariant> implicitInvariants,
        @JsonProperty("invariant_coverage") List<InvariantCoverage> invariantCoverage,
        @Nullable Runtime runtime,
        @JsonProperty("call_sites") List<CallSite> callSites,
        List<Chain> chains,
        @JsonProperty("failure_modes") List<FailureMode> failureModes,
        List<TaxonomyNode> taxonomy,
        @Nullable Progress progress,
        List<Capability> capabilities) {
    public Pipeline {
        if (version == null) version = "0.3.0";
        packs = packs == null ? List.of() : packs;
        implicitInvariants = implicitInvariants == null ? List.of() : implicitInvariants;
        invariantCoverage = invariantCoverage == null ? List.of() : invariantCoverage;
        callSites = callSites == null ? List.of() : callSites;
        chains = chains == null ? List.of() : chains;
        failureModes = failureModes == null ? List.of() : failureModes;
        taxonomy = taxonomy == null ? List.of() : taxonomy;
        capabilities = capabilities == null ? List.of() : capabilities;
    }

    public static Pipeline empty() {
        return new Pipeline("0.3.0", null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
