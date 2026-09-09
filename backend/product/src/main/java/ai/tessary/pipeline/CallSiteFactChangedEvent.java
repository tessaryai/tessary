// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import java.util.Set;

/**
 * Published in-process when a {@link CallSiteFact} lands on, changes on, or is cleared from one or
 * more call sites — the seam classifiers use to notice that history they already swept is now
 * scoreable, without {@code pipeline/} having to know they exist (the same shape as {@code
 * ProjectCreatedEvent}).
 *
 * <p>Consumers register an {@code @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution
 * = true)} so the fact is durable before anyone acts on it, and so a publish outside a transaction
 * still dispatches. A consumer must be <b>idempotent</b> and non-fatal: the write has already
 * committed, so throwing cannot undo it, and a duplicate event must cost nothing beyond the work it
 * describes.
 *
 * <p>Only genuine <em>changes</em> are published — re-writing the same schema or the same shape is
 * silent — so a consumer may treat every event as real work.
 *
 * @param projectId the project the call sites belong to.
 * @param fact which fact changed.
 * @param callSiteIds the call sites whose value of {@code fact} differs from what was stored. Never
 *     empty. Carried for provenance and logging: the classifier sweep cursor is per-signal and
 *     project-wide, so today's consumer rewinds whole signals rather than individual call sites.
 */
public record CallSiteFactChangedEvent(String projectId, CallSiteFact fact, Set<String> callSiteIds) {}
