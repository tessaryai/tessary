// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.facts;

import org.jspecify.annotations.Nullable;

/**
 * One settled trace as the fold reads it. The IDs are as stored; the fold trims them. {@code startedSecond} is whole
 * epoch seconds, as the reference fold reads them.
 *
 * @param hasParent the trace names a parent trace
 */
public record FoldTrace(
        @Nullable String userId,
        @Nullable String sessionId,
        @Nullable String threadId,
        @Nullable String name,
        @Nullable String callSiteId,
        @Nullable String projectVersionId,
        long startedSecond,
        @Nullable String inputPreview,
        boolean hasParent) {}
