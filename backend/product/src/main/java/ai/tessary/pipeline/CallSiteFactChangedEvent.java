// SPDX-License-Identifier: Apache-2.0
package ai.tessary.pipeline;

import java.util.Set;

/**
 * Published when a {@link CallSiteFact} lands on, changes on, or is cleared from call sites, so classifiers can rescore
 * history they already swept without {@code pipeline/} knowing they exist. Only genuine changes are published.
 * Consumers listen AFTER_COMMIT with {@code fallbackExecution = true} and must be idempotent and non-fatal: the write
 * has already committed. @param callSiteIds never empty; provenance only, since today's consumer rewinds whole signals
 */
public record CallSiteFactChangedEvent(String projectId, CallSiteFact fact, Set<String> callSiteIds) {}
