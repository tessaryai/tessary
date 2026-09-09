// SPDX-License-Identifier: Apache-2.0
/**
 * Generic leased job queue — the shared SKIP-LOCKED lease/claim machinery behind the per-kind queues
 * (embedding / signal / observer / synth) on the unified {@code job} table. The {@code pull} kind (no
 * lease claim) and the {@code usage_rollup} kind (different {@code claimed_at} model) are excluded by
 * design.
 *
 * <p>Every leased queue shares the same shape: one row per natural key, claimed {@code FOR UPDATE
 * SKIP LOCKED}, expired leases reclaimed while {@code attempts} is under a cap, and dead-lettered once
 * at/over it. {@link ai.tessary.open.jobqueue.LeasePolicy} carries the pure, I/O-free lease
 * decisions (batch size, lease duration, attempt cap) shared by every queue, so the
 * claim/reclaim/dead-letter SQL consumes one policy type instead of copy-pasted constants.
 */
@NullMarked
package ai.tessary.open.jobqueue;

import org.jspecify.annotations.NullMarked;
