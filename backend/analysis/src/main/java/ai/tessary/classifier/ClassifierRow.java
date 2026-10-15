// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A project-scoped classifier definition; {@code classifierKey} is unique per project. @param configJson opaque;
 * {@code null} falls back to the detector's defaults @param mode tenant-controlled, never bumps {@link #version} or is
 * clobbered by a catalog re-seed @param callSiteIds null for every call site, never empty; tenant-controlled like
 * {@link #mode}
 */
public record ClassifierRow(
        String id,
        String projectId,
        String classifierKey,
        String name,
        @Nullable String description,
        String detector,
        @Nullable String configJson,
        boolean builtIn,
        int version,
        boolean enabled,
        String mode,
        String createdAt,
        String updatedAt,
        @Nullable List<String> callSiteIds) {

    public ClassifierRow {
        callSiteIds = callSiteIds == null ? null : List.copyOf(callSiteIds);
    }

    /** Traffic with no call site is in scope only when the classifier runs everywhere. */
    public boolean runsOn(@Nullable String callSiteId) {
        return callSiteIds == null || (callSiteId != null && callSiteIds.contains(callSiteId));
    }

    /**
     * {@code discovery} favours recall (hits are human-reviewed); {@code tracking} favours precision because it feeds
     * metrics and alerts.
     */
    public static final class Mode {
        private Mode() {}

        public static final String DISCOVERY = "discovery";
        public static final String TRACKING = "tracking";
    }
}
