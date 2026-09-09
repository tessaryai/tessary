// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metering;

import org.jspecify.annotations.Nullable;

/**
 * An optional narrowing of an org LLM-usage read to one lane, one project and/or one model. A null
 * component means "don't filter on this axis"; the components AND together.
 *
 * <p>The values are the same keys the breakdown slices carry, so a client filters by echoing back a
 * key it was given. An empty string is a real key on the lane and model axes (a call that reported
 * neither), which is why the predicate compares against {@code COALESCE(col, '')} rather than treating
 * blank as absent.
 */
public record LlmUsageFilter(
        @Nullable String lane,
        @Nullable String projectId,
        @Nullable String model) {

    /** No narrowing — the whole org over the window. */
    public static final LlmUsageFilter NONE = new LlmUsageFilter(null, null, null);
}
