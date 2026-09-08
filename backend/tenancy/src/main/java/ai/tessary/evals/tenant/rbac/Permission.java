// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant.rbac;

/**
 * The discrete actions RBAC gates at the org boundary. Each sensitive controller path
 * names the permission it requires; {@link RolePermissions} maps roles to the set they
 * hold. Keeping actions coarse-but-explicit (rather than one-per-endpoint) keeps the
 * matrix readable while still letting a viewer be blocked from every mutation and a
 * billing-only role be blocked from organization content.
 */
public enum Permission {
    /** Read organization/project content (members list, projects, runs, settings views). */
    ORG_VIEW,
    /** Create/rename/archive projects and edit organization-level (non-billing) settings. */
    ORG_MANAGE,
    /** Irreversible organization lifecycle: rename/archive/delete the org, transfer ownership. */
    ORG_ADMIN,
    /** Invite members, change their roles, and remove them. */
    MEMBERS_MANAGE,
    /** View usage and manage the billing relationship (plan, seats). */
    BILLING_MANAGE,
    /**
     * Turn the org's capabilities on and off (Settings → Features). Owner and admin only.
     *
     * <p>Its own permission rather than a reuse of an existing one, because none of them is the right set.
     * {@code ORG_MANAGE} is held by MEMBER too, and a member flipping automatic triage on would start spending
     * the org's model budget; {@code ORG_ADMIN} is owner-only, which locks out the admin who actually runs the
     * deployment; {@code MEMBERS_MANAGE} has the right role set and the wrong meaning to hang this off.
     */
    CAPABILITIES_MANAGE,
    /**
     * Change how long a project keeps its data (#1205). Its own permission for the same reason as
     * {@link #CAPABILITIES_MANAGE}: {@code ORG_MANAGE} would let any member schedule the irreversible
     * deletion of months of traces within the hour.
     */
    RETENTION_MANAGE;
}
