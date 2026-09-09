// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

/**
 * How many organizations a single user may own, via the org-creation and ownership-transfer paths.
 * Both paths enforce it inside a transaction under the same per-owner lock
 * ({@code OrganizationRepository#lockOrgCreationFor}): creation in {@code TenantService#bootstrapOrg},
 * transfer in {@code TenantService#transferOwnership}.
 *
 * <p>This build self-hosts one org per install: {@code createOrg} exists only to bootstrap that
 * first org on a fresh signup, so the default caps at 1. {@link OrganizationController} depends
 * only on this interface, so a build wanting a different ceiling can supply its own implementation.
 *
 * <p>This is deliberately its own SPI rather than a reuse of {@code CapabilityService}, since
 * capping org count per user has no org to key an override against (the limit applies before the
 * second org exists), so it gets a one-method interface of its own instead.
 */
public interface OrgCreationLimit {

    /** The maximum number of organizations one user may own at a time. */
    int maxOwnedOrgsPerUser();
}
