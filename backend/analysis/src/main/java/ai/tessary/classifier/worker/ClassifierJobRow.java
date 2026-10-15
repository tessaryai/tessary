// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.model.JobStatus;
import org.jspecify.annotations.Nullable;

/**
 * The leased sweep coordinator for one signal: one row per {@code (project, signal)}, claimed
 * {@code FOR UPDATE SKIP LOCKED}. @param cursorAt keyset high-water mark; the worker claims observations strictly after
 * {@code (cursorAt, cursorId)}; null sweeps from the beginning @param cursorId tiebreaker that keeps the cursor
 * gap-free across rows sharing one {@code created_at} @param pageRetries consecutive holds of the page past the cursor;
 * see {@code ClassifierJobRepository#holdPage}
 */
public record ClassifierJobRow(
        String id,
        String projectId,
        String classifierId,
        String status,
        @Nullable String cursorAt,
        @Nullable String cursorId,
        @Nullable String leaseOwner,
        @Nullable String leaseExpiresAt,
        int attempts,
        @Nullable String lastError,
        String createdAt,
        String updatedAt,
        int pageRetries) {
    public static final String PENDING = JobStatus.PENDING;

    public static final String CLAIMED = JobStatus.CLAIMED;

    /**
     * Terminal past the attempt cap, unlike retryable {@link JobStatus#FAILED}: every heartbeat re-pends signal jobs,
     * so exhaustion needs a state that re-pend skips until the dead-letter cooldown elapses.
     */
    public static final String DEAD = JobStatus.DEAD;
}
