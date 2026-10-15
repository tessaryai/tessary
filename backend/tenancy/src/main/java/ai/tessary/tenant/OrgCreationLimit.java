// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

/**
 * How many organizations a user may own, enforced under a per-owner lock
 * ({@code OrganizationRepository#lockOrgCreationFor}). Defaults to 1, since this build self-hosts one org. Its own SPI
 * rather than {@code CapabilityService}, which has no org to key on before the second org exists.
 */
public interface OrgCreationLimit {

    int maxOwnedOrgsFor(String userId);
}
