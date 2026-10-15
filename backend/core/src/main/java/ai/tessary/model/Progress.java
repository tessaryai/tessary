// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * Synthesis progress from {@code pipeline/meta.yaml} (evals plugin v0.7). Medium and low severity failures are deferred
 * in the first sweep and counted here, so the UI can show how much still needs {@code --complete}.
 */
public record Progress(
        @JsonProperty("sites_completed") @Nullable Integer sitesCompleted,
        @JsonProperty("sites_total") @Nullable Integer sitesTotal,
        @JsonProperty("deferred_failure_count") @Nullable Integer deferredFailureCount) {}
