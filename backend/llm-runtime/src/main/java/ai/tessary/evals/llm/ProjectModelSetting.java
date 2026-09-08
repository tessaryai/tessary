// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm;

import ai.tessary.evals.llmspi.ModelLane;
import ai.tessary.evals.llmspi.ServiceTier;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

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
        @JsonProperty("service_tier") ServiceTier serviceTier,
        /**
         * The reasoning effort to run this lane at, or null to send no reasoning parameter and let the
         * model use its own default.
         *
         * <p>Null is the meaningful absence, not a missing value: it is what every row written before
         * this column existed means, and it is the only correct value for a model with no effort
         * control at all. Which levels a given model accepts lives in {@link BedrockModelProfile}, so
         * an effort that a later model change invalidates degrades to null on read rather than failing
         * the lane — the same posture {@link ServiceTier} already has.
         */
        @JsonProperty("reasoning_effort") @Nullable String reasoningEffort,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {}
