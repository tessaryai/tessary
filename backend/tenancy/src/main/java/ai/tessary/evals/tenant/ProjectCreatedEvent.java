// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

/**
 * Published in-process after a {@code project} row commits — the seam slices use to provision a
 * project's starting state without {@code tenant/} having to know they exist. A consumer registers
 * an {@code @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)} so it only
 * provisions projects that actually persisted.
 *
 * <p>Provisioning off this event must be <b>idempotent</b> and non-fatal: a consumer that throws
 * cannot roll the project back (the transaction has already committed), and the same project is
 * re-provisioned by whatever self-heal path its slice owns (e.g. {@code
 * ClassifierService#resyncBuiltIns} on the signal heartbeat) for projects that predate the event.
 *
 * @param projectId the newly created project.
 */
public record ProjectCreatedEvent(String projectId) {}
