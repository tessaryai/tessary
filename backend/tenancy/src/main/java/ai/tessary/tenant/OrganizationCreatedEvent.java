// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

/**
 * Published right after every {@code organization} insert, so a build can provision an org's starting state; nothing in
 * this build listens, but Tessary Cloud does, so keep publishing it. Only {@link TenantService#bootstrapOrg} is
 * transactional; on the sign-in paths the org is already written, so listeners must be idempotent and survive their own
 * failure.
 */
public record OrganizationCreatedEvent(String orgId) {}
