// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.storage;

import org.jspecify.annotations.Nullable;

/**
 * A curated {@code failure_mode} — the graduation of the raw clustered {@code issue}. It has a
 * stable slug {@code key} (unique per project) so its identity survives a recompute (UPSERT on
 * {@code (project_id, key)} rather than DELETE+INSERT), a real lifecycle {@code status}
 * ({@code proposed|open|resolved|regressed|muted}), and a coarse {@code severity}
 * ({@code low|med|high|critical}). Its clustered members are {@link FailureModeInstanceRow}s.
 *
 * <p>Impact ({@code impactCount}/{@code impactRate}) and the version-lineage fields are populated by
 * the impact rollup; they default to 0/NULL here.
 */
public record FailureModeRow(
        String id,
        String projectId,
        String key,
        String name,
        @Nullable String description,
        String status,
        @Nullable String severity,
        @Nullable String surface,
        @Nullable String signature,
        @Nullable String centroidRef,
        int impactCount,
        @Nullable Double impactRate,
        @Nullable String firstSeenVersionId,
        @Nullable String regressedVersionId,
        @Nullable String firstSeenAt,
        @Nullable String lastSeenAt,
        String createdAt,
        String updatedAt,
        @Nullable String attributes) {

    /** {@code status} values. */
    public static final class Status {
        private Status() {}

        public static final String PROPOSED = "proposed";
        public static final String OPEN = "open";
        public static final String RESOLVED = "resolved";
        public static final String REGRESSED = "regressed";
        public static final String MUTED = "muted";
    }

    /** {@code severity} values. */
    public static final class Severity {
        private Severity() {}

        public static final String LOW = "low";
        public static final String MED = "med";
        public static final String HIGH = "high";
        public static final String CRITICAL = "critical";
    }
}
