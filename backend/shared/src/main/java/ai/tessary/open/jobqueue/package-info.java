// SPDX-License-Identifier: Apache-2.0
/**
 * Vocabulary and SQL for the unified {@code job} table. Every leased queue has one row per natural key, claimed
 * {@code FOR UPDATE SKIP LOCKED}, reclaimed while {@code attempts} is under a cap, and dead-lettered at it.
 */
@NullMarked
package ai.tessary.open.jobqueue;

import org.jspecify.annotations.NullMarked;
