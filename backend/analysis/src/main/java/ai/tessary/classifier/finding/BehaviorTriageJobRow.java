// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.finding;

import ai.tessary.model.JobStatus;
import org.jspecify.annotations.Nullable;

/**
 * One triage job ({@code kind='triage'}); the finding id is the whole payload, with no exemplar trace: handing an agent
 * a chosen instance decides what it anchors on. Dedupe key {@code <project_id>:<finding_id>}. Terminal is {@code dead},
 * not {@code failed}, so a human's <em>Run triage</em> can revive it after the cooldown while automatic paths cannot.
 */
public record BehaviorTriageJobRow(
        String id,
        String projectId,
        String findingId,
        String status,
        @Nullable String leaseOwner,
        @Nullable String leaseExpiresAt,
        int attempts,
        @Nullable String lastError,
        String createdAt,
        String updatedAt) {

    /**
     * Shared with the other cooldown-gated queues so the park and the revival cannot drift. Migration 0022 moved old
     * {@code failed} rows here.
     */
    public static final String DEAD = JobStatus.DEAD;
}
