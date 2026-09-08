// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant.rbac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The RBAC matrix is the single source of truth for what each role may do; a wrong cell
 * here silently lets (or blocks) a real mutation, so every load-bearing cell is pinned
 * explicitly.
 */
class RolePermissionsTest {

    @Test
    void owner_holdsEveryPermission() {
        for (Permission p : Permission.values()) {
            assertTrue(RolePermissions.allows(Role.OWNER, p), "owner should hold " + p);
        }
    }

    @Test
    void viewer_canViewButNotMutate() {
        assertTrue(RolePermissions.allows(Role.VIEWER, Permission.ORG_VIEW));
        assertFalse(RolePermissions.allows(Role.VIEWER, Permission.ORG_MANAGE));
        assertFalse(RolePermissions.allows(Role.VIEWER, Permission.ORG_ADMIN));
        assertFalse(RolePermissions.allows(Role.VIEWER, Permission.MEMBERS_MANAGE));
        assertFalse(RolePermissions.allows(Role.VIEWER, Permission.BILLING_MANAGE));
        assertFalse(RolePermissions.allows(Role.VIEWER, Permission.CAPABILITIES_MANAGE));
        assertFalse(RolePermissions.allows(Role.VIEWER, Permission.RETENTION_MANAGE));
    }

    @Test
    void member_canManageContentButNotMembersOrBilling() {
        assertTrue(RolePermissions.allows(Role.MEMBER, Permission.ORG_VIEW));
        assertTrue(RolePermissions.allows(Role.MEMBER, Permission.ORG_MANAGE));
        assertFalse(RolePermissions.allows(Role.MEMBER, Permission.MEMBERS_MANAGE));
        assertFalse(RolePermissions.allows(Role.MEMBER, Permission.ORG_ADMIN));
        assertFalse(RolePermissions.allows(Role.MEMBER, Permission.BILLING_MANAGE));
        assertFalse(RolePermissions.allows(Role.MEMBER, Permission.CAPABILITIES_MANAGE));
        assertFalse(RolePermissions.allows(Role.MEMBER, Permission.RETENTION_MANAGE));
    }

    @Test
    void admin_managesMembersAndContentButNotLifecycleOrBilling() {
        assertTrue(RolePermissions.allows(Role.ADMIN, Permission.MEMBERS_MANAGE));
        assertTrue(RolePermissions.allows(Role.ADMIN, Permission.ORG_MANAGE));
        assertTrue(RolePermissions.allows(Role.ADMIN, Permission.ORG_VIEW));
        // Owner-only irreversible lifecycle (delete/transfer) and billing stay off-limits.
        assertFalse(RolePermissions.allows(Role.ADMIN, Permission.ORG_ADMIN));
        assertFalse(RolePermissions.allows(Role.ADMIN, Permission.BILLING_MANAGE));
        // The admin who runs the deployment is exactly who turns a capability on or off.
        assertTrue(RolePermissions.allows(Role.ADMIN, Permission.CAPABILITIES_MANAGE));
        assertTrue(RolePermissions.allows(Role.ADMIN, Permission.RETENTION_MANAGE));
    }

    @Test
    void billing_reachesBillingOnlyAndSeesNoOrgContent() {
        assertTrue(RolePermissions.allows(Role.BILLING, Permission.BILLING_MANAGE));
        assertFalse(RolePermissions.allows(Role.BILLING, Permission.ORG_VIEW));
        assertFalse(RolePermissions.allows(Role.BILLING, Permission.ORG_MANAGE));
        assertFalse(RolePermissions.allows(Role.BILLING, Permission.MEMBERS_MANAGE));
        assertFalse(RolePermissions.allows(Role.BILLING, Permission.CAPABILITIES_MANAGE));
    }

    /**
     * The capability switch is owner + admin and nothing else. MEMBER is the cell that matters: it holds
     * {@code ORG_MANAGE}, so reusing that permission — the obvious shortcut — would have let any member turn
     * automatic triage on and start spending the org's model budget.
     */
    @Test
    void onlyOwnerAndAdminChangeCapabilities() {
        assertTrue(RolePermissions.allows(Role.OWNER, Permission.CAPABILITIES_MANAGE));
        assertTrue(RolePermissions.allows(Role.ADMIN, Permission.CAPABILITIES_MANAGE));
        assertFalse(RolePermissions.allows(Role.MEMBER, Permission.CAPABILITIES_MANAGE));
        assertFalse(RolePermissions.allows(Role.VIEWER, Permission.CAPABILITIES_MANAGE));
        assertFalse(RolePermissions.allows(Role.BILLING, Permission.CAPABILITIES_MANAGE));
    }

    @Test
    void onlyOwnerAndBillingReachBilling() {
        assertTrue(RolePermissions.allows(Role.OWNER, Permission.BILLING_MANAGE));
        assertTrue(RolePermissions.allows(Role.BILLING, Permission.BILLING_MANAGE));
        assertFalse(RolePermissions.allows(Role.ADMIN, Permission.BILLING_MANAGE));
        assertFalse(RolePermissions.allows(Role.MEMBER, Permission.BILLING_MANAGE));
        assertFalse(RolePermissions.allows(Role.VIEWER, Permission.BILLING_MANAGE));
    }

    @Test
    void fromWire_roundTripsAndDegradesUnknownToMember() {
        for (Role r : Role.values()) {
            assertEquals(Optional.of(r), Role.fromWire(r.wire()));
        }
        assertEquals(Optional.of(Role.VIEWER), Role.fromWire("  VIEWER "));
        assertEquals(Optional.empty(), Role.fromWire("superuser"));
        assertEquals(Role.MEMBER, Role.fromWireOrMember("superuser"));
        assertEquals(Role.MEMBER, Role.fromWireOrMember(null));
    }
}
