// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant.rbac;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Maps {@link Role} to its {@link Permission}s for server-side guards. Admin manages members, settings and capabilities
 * but not deletion or billing; member manages content only; viewer mutates nothing; billing sees no organization
 * content at all.
 */
public final class RolePermissions {

    private static final Map<Role, Set<Permission>> MATRIX = buildMatrix();

    private RolePermissions() {}

    private static Map<Role, Set<Permission>> buildMatrix() {
        Map<Role, Set<Permission>> m = new EnumMap<>(Role.class);
        m.put(
                Role.OWNER,
                EnumSet.of(
                        Permission.ORG_VIEW,
                        Permission.ORG_MANAGE,
                        Permission.ORG_ADMIN,
                        Permission.MEMBERS_MANAGE,
                        Permission.BILLING_MANAGE,
                        Permission.CAPABILITIES_MANAGE,
                        Permission.RETENTION_MANAGE));
        m.put(
                Role.ADMIN,
                EnumSet.of(
                        Permission.ORG_VIEW,
                        Permission.ORG_MANAGE,
                        Permission.MEMBERS_MANAGE,
                        Permission.CAPABILITIES_MANAGE,
                        Permission.RETENTION_MANAGE));
        m.put(Role.MEMBER, EnumSet.of(Permission.ORG_VIEW, Permission.ORG_MANAGE));
        m.put(Role.VIEWER, EnumSet.of(Permission.ORG_VIEW));
        m.put(Role.BILLING, EnumSet.of(Permission.BILLING_MANAGE));
        return m;
    }

    public static boolean allows(Role role, Permission permission) {
        return MATRIX.getOrDefault(role, Set.of()).contains(permission);
    }
}
