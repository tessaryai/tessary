// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.open.jobqueue;

import java.util.List;

/**
 * The generic leased-queue seam: a single {@code kind}'s view over the unified {@code job} table,
 * consuming a {@link LeasePolicy} for its claim / reclaim / dead-letter decisions. The per-feature queues
 * (signal / observer / synth) implement this over their domain job
 * type {@code J}, delegating the SKIP-LOCKED machinery to the shared {@code JobRepository} and mapping the
 * raw {@link JobRow} to/from {@code J}. Non-leased kinds (pull / usage_rollup) keep their own claim SQL and
 * do not implement this interface (they were always excluded from the lease machinery).
 *
 * @param <J> the domain job type this queue claims (a per-kind row shape mapped from {@link JobRow}).
 */
public interface LeasedJobQueue<J> {

    /** The {@code job.kind} this queue owns. */
    String kind();

    /**
     * Claim up to {@link LeasePolicy#batch()} due jobs of {@link #kind()} under a fresh lease (SKIP LOCKED),
     * incrementing attempts; reclaims expired-lease jobs still under the attempt cap. Oldest-first.
     */
    List<J> claim(String leaseOwner, LeasePolicy policy);

    /** Mark a claimed job terminal-done. */
    void markDone(String id);

    /** Mark a claimed job failed with a short, secret-free reason. */
    void markFailed(String id, String error);

    /**
     * Dead-letter jobs of {@link #kind()} whose lease expired at/over the attempt cap (hung or crashed past
     * their retry budget). Returns the number dead-lettered.
     */
    int failExhausted(LeasePolicy policy);
}
