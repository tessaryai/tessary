// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.jobqueue;

import java.time.Duration;
import java.time.Instant;

/**
 * Dataset-agnostic lease policy shared by the SKIP-LOCKED lease queues (embedding / signal / observer /
 * synth): the batch size a worker claims at once, the lease duration before a claimed job is
 * reclaimable, and the attempt cap past which a hung job is dead-lettered.
 *
 * <p>The pure lease decisions of the {@code LeasedJobQueue} machinery, kept in one type so all
 * queues share one policy. It performs no I/O — the SQL claim / reclaim / dead-letter statements
 * consume these values (mirroring
 * {@code lease_expires_at} and the {@code attempts >= maxAttempts} predicate).
 *
 * <p>The shared {@code JobRepository} consumes this type directly for its kind-scoped claim /
 * dead-letter statements; the per-feature {@code *JobRepository.claimBatch} signatures still take
 * raw {@code (batch, leaseSeconds, maxAttempts)} primitives.
 */
public record LeasePolicy(int batch, Duration leaseDuration, int maxAttempts) {

    /** Validates the knobs: batch and attempt cap are at least one, and the lease duration is positive. */
    public LeasePolicy {
        if (batch < 1) {
            throw new IllegalArgumentException("batch must be >= 1, was " + batch);
        }
        if (leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive, was " + leaseDuration);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, was " + maxAttempts);
        }
    }

    /** The lease expiry for a job claimed at {@code now} (mirrors the {@code lease_expires_at} column). */
    public Instant expiresAt(Instant now) {
        return now.plus(leaseDuration);
    }

    /**
     * Whether a claimed job whose lease has expired should be dead-lettered rather than reclaimed: true
     * once {@code attempts} has reached the cap (mirrors the {@code attempts >= :maxAttempts} predicate).
     */
    public boolean isExhausted(int attempts) {
        return attempts >= maxAttempts;
    }
}
