// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant.rbac;

/** Coarse but explicit RBAC actions; {@link RolePermissions} maps roles to them. */
public enum Permission {
    ORG_VIEW,
    ORG_MANAGE,
    ORG_ADMIN,
    MEMBERS_MANAGE,
    BILLING_MANAGE,
    /**
     * Owner and admin only. Not {@code ORG_MANAGE}, which members hold and which would let them start spending model
     * budget; not {@code ORG_ADMIN}, which is owner-only.
     */
    CAPABILITIES_MANAGE,
    /** Not {@code ORG_MANAGE}: any member could schedule irreversible deletion of months of traces. */
    RETENTION_MANAGE;
}
