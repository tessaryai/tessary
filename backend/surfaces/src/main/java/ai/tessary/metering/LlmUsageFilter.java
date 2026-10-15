// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import org.jspecify.annotations.Nullable;

/**
 * Narrows an org LLM-usage read by lane, project and/or model; null means no filter on that axis. Values echo breakdown
 * keys. An empty string is a real key on lane and model, hence {@code COALESCE(col, '')}.
 */
public record LlmUsageFilter(
        @Nullable String lane,
        @Nullable String projectId,
        @Nullable String model) {

    public static final LlmUsageFilter NONE = new LlmUsageFilter(null, null, null);
}
