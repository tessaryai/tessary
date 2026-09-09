// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

/**
 * How many organizations a single user may OWN, via the org-creation and ownership-transfer paths.
 * Both paths enforce it inside a transaction under the same per-owner lock
 * ({@code OrganizationRepository#lockOrgCreationFor}): creation in {@code TenantService#bootstrapOrg}
 * (#1019), transfer in {@code TenantService#transferOwnership} (#1028).
 *
 * <p>The open edition self-hosts one org per install — {@code createOrg} exists only to bootstrap
 * that first org on a fresh signup, so the open default caps at 1. The hosted edition lets a user
 * run several orgs from one account, so the paid override raises the ceiling (see
 * {@code tessary-paid/plan/tenant/PaidOrgCreationLimit}) without either side reaching across the
 * open/paid boundary: {@link OrganizationController} depends only on this interface.
 *
 * <p>This is deliberately its own SPI rather than a reuse of {@code CapabilityService} — see that
 * class's own javadoc, which scopes itself to capabilities whose classifier code is genuinely absent
 * from the open build. Capping org COUNT per user has no org to key an override against (the limit
 * applies before the second org exists), so it does not fit that seam and gets a one-method
 * interface of its own instead.
 */
public interface OrgCreationLimit {

    /** The maximum number of organizations one user may own at a time. */
    int maxOwnedOrgsPerUser();
}
