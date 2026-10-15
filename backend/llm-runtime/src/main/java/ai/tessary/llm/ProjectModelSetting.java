// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.llmspi.ModelLane;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A project's model choice for one {@link ModelLane} ({@code project_model_setting}). The absence of
 * a row is the meaningful default — see {@link ProjectModelSettingRepository} — so this record only
 * ever describes an explicit opt-in.
 *
 * <p>{@code modelKey} is a logical {@link BedrockModelProfile} key ({@code amazon.nova-2-lite}), not
 * a full inference-profile id: the id carries a region-routing prefix and a version stamp that are
 * deployment details, and pinning them here would freeze a project onto a snapshot AWS eventually
 * retires.
 */
public record ProjectModelSetting(
        @JsonProperty("project_id") String projectId,
        ModelLane lane,
        @JsonProperty("model_key") String modelKey,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {}
