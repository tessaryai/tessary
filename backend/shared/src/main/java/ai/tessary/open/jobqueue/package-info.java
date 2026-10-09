// SPDX-License-Identifier: Apache-2.0
/**
 * Shared job-queue vocabulary and SQL for the unified {@code job} table: the {@code kind} / status
 * constants ({@link ai.tessary.open.jobqueue.JobRow}) and the SKIP-LOCKED claim / dead-letter statements
 * ({@link ai.tessary.open.jobqueue.LeasedJobSql}) every leased kind runs.
 *
 * <p>Every leased queue shares the same shape: one row per natural key, claimed {@code FOR UPDATE
 * SKIP LOCKED}, expired leases reclaimed while {@code attempts} is under a cap, and dead-lettered once
 * at/over it.
 */
@NullMarked
package ai.tessary.open.jobqueue;

import org.jspecify.annotations.NullMarked;
