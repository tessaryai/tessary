// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

/**
 * Published after a {@code project} row commits, so slices can provision a project without {@code tenant/} knowing
 * them. Consumers listen AFTER_COMMIT with {@code fallbackExecution = true} and must be idempotent and non-fatal: they
 * cannot roll the project back, and their slice's self-heal (e.g. {@code ClassifierService#resyncBuiltIns}) covers
 * older projects.
 */
public record ProjectCreatedEvent(String projectId) {}
