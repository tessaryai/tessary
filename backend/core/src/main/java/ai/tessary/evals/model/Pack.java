// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One engaged pack from a synthesis run. Mirrors pipeline.yaml's
 * {@code packs[]} block introduced in evals plugin v0.3.
 *
 * <p>Packs are high-level concern bundles (security / quality / reliability /
 * brand) that contribute failure modes at synthesis time. The orchestrator
 * runs each pack's interview against the user (skipping questions already
 * answered by code analysis) and records the resolved Q&A here.
 *
 * <p>{@code tierHint} is informational metadata only — the consuming product
 * gates commercial enablement on it; the platform itself never enforces.
 *
 * <p>{@code interviewAnswers} is a free-form map of question id → answer
 * record (answer / source / evidence). Held as untyped objects because each
 * pack's interview is bespoke.
 *
 * <p>{@code contentDigest} is the SHA-256 prefix over the pack's manifest +
 * interview prompt + failure-synthesis prompt; lets re-runs detect when a
 * pack itself changed.
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
