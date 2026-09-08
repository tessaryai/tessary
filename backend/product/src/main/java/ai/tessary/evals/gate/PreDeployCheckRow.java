// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.gate;

import org.jspecify.annotations.Nullable;

/**
 * One registered pre-deploy check: a durable "this discovered production signal implicates this
 * LLM-product surface — intensify pre-merge checks there" registration. One row per implicated surface
 * (the {@code diff_classification} per-surface grain), keyed to {@link ai.tessary.evals.model.TouchedSurface}
 * wire names so a future change's resolved surfaces join straight against these rows.
 *
 * <p><b>There is one provenance, so the row no longer carries a discriminator.</b> A check keys on the
 * natural key {@code (projectId, classifierId, surface)}, enforced by a unique index so registration stays a
 * first-write-wins no-op. There used to be a second provenance — a {@code feedback} check anchored on
 * the observation a feedback verdict hung off — with its own column, its own partial index and its own
 * branch of a {@code source} CHECK; 0083 deleted its rows and 0092 dropped what was left of the
 * discriminator once it had exactly one legal value.
 *
 * @param classifierId the discovery DEFINITION whose firing registered this check, NOT NULL in the schema —
 *     it is half the natural key, and a NULL would defeat the unique index it is enforced by.
 * @param failureModeId the LEARNED-mapping seam: {@code null} today because a signal carries no
 *     learned failure mode yet. When a learned mapping keys signal events to failure modes the registering
 *     writer fills this in and the read side can rank per-mode — without a schema change.
 * @param intensity coarse tier ({@code high|medium|low}) carried from the registering classifier's severity.
 * @param status {@code active} or {@code dismissed} — the tenant-controllable lifecycle. A {@code dismissed}
 *     check is skipped by the read side and is never resurrected by a re-discovery (insert-if-absent only).
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

    /** The lifecycle states of a registered check. */
    public static final class Status {
        private Status() {}

        public static final String ACTIVE = "active";
        public static final String DISMISSED = "dismissed";
    }

    /** Coarse intensity tiers, mirroring routing's {@code RoutedGrader.intensity}. */
    public static final class Intensity {
        private Intensity() {}

        public static final String HIGH = "high";
        public static final String MEDIUM = "medium";
        public static final String LOW = "low";
    }
}
