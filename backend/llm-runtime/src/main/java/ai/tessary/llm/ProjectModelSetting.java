// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.llmspi.ModelLane;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A project's explicit model choice for one {@link ModelLane}; no row is the default. {@code modelKey} is a logical
 * {@link BedrockModelProfile} key, not an inference-profile id, whose region prefix and version stamp would pin a
 * snapshot AWS eventually retires.
 */
public record ProjectModelSetting(
        @JsonProperty("project_id") String projectId,
        ModelLane lane,
        @JsonProperty("model_key") String modelKey,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {}
