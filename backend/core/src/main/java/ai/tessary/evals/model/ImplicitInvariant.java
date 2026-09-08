// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A product-wide rule with evidence + confidence. `applies_to` is a string or a list. */
public record ImplicitInvariant(
        String name,
        String description,
        @Schema(allowableValues = {"high", "medium", "low"}) String confidence,
        List<String> evidence,
        @JsonProperty("applies_to") Object appliesTo,
        @JsonProperty("enforced_in") List<String> enforcedIn,
        @JsonProperty("likely_gap_in") List<String> likelyGapIn) {
    public ImplicitInvariant {
        evidence = evidence == null ? List.of() : evidence;
        enforcedIn = enforcedIn == null ? List.of() : enforcedIn;
        likelyGapIn = likelyGapIn == null ? List.of() : likelyGapIn;
        if (appliesTo == null) appliesTo = "all_call_sites";
    }

    @JsonCreator
    public static ImplicitInvariant create(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("confidence") String confidence,
            @JsonProperty("evidence") List<String> evidence,
            @JsonProperty("applies_to") Object appliesTo,
            @JsonProperty("enforced_in") List<String> enforcedIn,
            @JsonProperty("likely_gap_in") List<String> likelyGapIn) {
        return new ImplicitInvariant(name, description, confidence, evidence, appliesTo, enforcedIn, likelyGapIn);
    }
}
