// SPDX-License-Identifier: Apache-2.0
package ai.tessary.gate;

import org.jspecify.annotations.Nullable;

/**
 * One registered pre-deploy check per implicated surface, keyed to {@link ai.tessary.model.TouchedSurface} wire names.
 * Natural key {@code (projectId, classifierId, surface)}, unique, so registration is first-write-wins. @param
 * failureModeId the learned-mapping seam, null today @param status {@code active} or {@code dismissed}; a dismissed
 * check is never resurrected by re-discovery
 */
public record PreDeployCheckRow(
        String id,
        String projectId,
        String classifierId,
        String surface,
        @Nullable String failureModeId,
        String intensity,
        String status,
        String createdAt,
        String updatedAt) {

    public static final class Status {
        private Status() {}

        public static final String ACTIVE = "active";
        public static final String DISMISSED = "dismissed";
    }

    public static final class Intensity {
        private Intensity() {}

        public static final String HIGH = "high";
        public static final String MEDIUM = "medium";
        public static final String LOW = "low";
    }
}
