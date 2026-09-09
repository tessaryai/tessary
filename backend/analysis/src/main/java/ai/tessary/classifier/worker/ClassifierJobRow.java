// SPDX-License-Identifier: Apache-2.0
package ai.tessary.classifier.worker;

import ai.tessary.model.JobStatus;
import org.jspecify.annotations.Nullable;

/**
 * The leased sweep coordinator for one signal, mirroring {@code observer_job}: one row
 * per {@code (project, signal)}, claimed {@code FOR UPDATE SKIP LOCKED} so running N backends is safe.
 *
 * @param cursorAt the {@code observation.created_at} component of the keyset high-water mark this signal
 *     has swept up to; the worker claims observations strictly after {@code (cursorAt, cursorId)}, then
 *     advances them. Monotonic + resumable; {@code null} sweeps from the beginning.
 * @param cursorId the {@code observation.id} tiebreaker of the keyset cursor, making it gap-free across a
 *     batch boundary that falls inside a group of observations sharing one {@code created_at}.
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
        String updatedAt) {
    public static final String PENDING = JobStatus.PENDING;
    public static final String CLAIMED = JobStatus.CLAIMED;
    public static final String DONE = JobStatus.DONE;
    public static final String FAILED = JobStatus.FAILED;

    /**
     * Terminal past the attempt cap, distinct from {@link #FAILED} (still retryable). A signal job is
     * resurrected by every heartbeat's re-pend, so its cap-crossing needs a state that routine re-pend
     * won't touch until the dead-letter cooldown elapses. Both exhaustion legs write it: a fast-failing
     * sweep via {@code ClassifierJobRepository#markFailed}, and a hung/crashed sweep whose lease expired via
     * {@code ClassifierJobRepository#failExhausted} — see {@code #markPending} for the cooldown-gated
     * revival.
     */
    public static final String DEAD = JobStatus.DEAD;
}
