// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

/**
 * Published in-process right after an {@code organization} row is inserted, on every path that creates
 * one: the personal org minted at a first sign-in, the first local mirror of a WorkOS org, and
 * {@link TenantService#bootstrapOrg}. The seam a build uses to provision an org's starting state without
 * {@code tenant/} having to know it exists. Nothing in this build listens; Tessary Cloud listens to
 * give each new org its plan and starting credit, so keep publishing it.
 *
 * <p>Only {@code bootstrapOrg} runs in a transaction, so only there does a plain {@code @EventListener}
 * commit or roll back together with the org. On the two sign-in paths the org is already written when
 * the event fires, so provisioning off it must be idempotent and must cope with a listener failure
 * leaving the org unprovisioned.
 *
 * @param orgId the newly created organization.
 */
public record OrganizationCreatedEvent(String orgId) {}
