// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One engaged pack from a synthesis run (pipeline.yaml {@code packs[]}, evals plugin v0.3). {@code tierHint} is
 * informational; the platform never enforces it. {@code interviewAnswers} is untyped because each pack's interview is
 * bespoke. {@code contentDigest} lets re-runs detect a changed pack.
 */
public record Pack(
        String id,
        String name,
        String version,

        @Nullable @Schema(allowableValues = {"free", "included", "addon"}) @JsonProperty("tier_hint")
        String tierHint,

        @Nullable @Schema(allowableValues = {"auto", "explicit", "tier_default"}) @JsonProperty("enabled_by")
        String enabledBy,

        @JsonProperty("interview_answers") Map<String, Object> interviewAnswers,
        @JsonProperty("contributes_compliance_tags") List<String> contributesComplianceTags,
        @Nullable @JsonProperty("content_digest") String contentDigest) {
    public Pack {
        if (interviewAnswers == null) interviewAnswers = Map.of();
        if (contributesComplianceTags == null) contributesComplianceTags = List.of();
    }
}
