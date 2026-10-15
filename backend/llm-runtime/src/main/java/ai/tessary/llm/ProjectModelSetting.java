// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.llmspi.ModelLane;
import ai.tessary.llmspi.ServiceTier;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * A project's explicit model choice for one {@link ModelLane}; no row is the default. {@code modelKey} is a logical
 * {@link BedrockModelProfile} key, not an inference-profile id, whose region prefix and version stamp would pin a
 * snapshot AWS eventually retires.
 */
public record ProjectModelSetting(
        @JsonProperty("project_id") String projectId,
        ModelLane lane,
        @JsonProperty("model_key") String modelKey,
        @JsonProperty("service_tier") ServiceTier serviceTier,
        /**
         * Null sends no reasoning parameter. An effort a later model change invalidates degrades to null on read rather
         * than failing the lane, as {@link ServiceTier} does.
         */
        @JsonProperty("reasoning_effort") @Nullable String reasoningEffort,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {}
