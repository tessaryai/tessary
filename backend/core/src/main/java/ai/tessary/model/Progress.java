// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * Synthesis-progress summary written into {@code pipeline/meta.yaml} under the
 * {@code progress:} key by evals plugin v0.7's phased flow ({@code finalize.py}).
 *
 * <p>Present only on partial bundles; a fully-synthesised bundle still carries
 * it with {@code deferredFailureCount = 0} and {@code sitesCompleted ==
 * sitesTotal}. Medium/low-severity failures are deferred during the first sweep
 * (no grader emitted yet) and counted here so the UI can show how much of the
 * repo still needs {@code --complete}.
 */
public record Progress(
        @JsonProperty("sites_completed") @Nullable Integer sitesCompleted,
        @JsonProperty("sites_total") @Nullable Integer sitesTotal,
        @JsonProperty("deferred_failure_count") @Nullable Integer deferredFailureCount) {}
