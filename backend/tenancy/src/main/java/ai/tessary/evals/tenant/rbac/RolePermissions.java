// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant.rbac;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth mapping {@link Role} → the {@link Permission}s it grants.
 * Server-side guards (and the {@code /api/me/orgs} affordance hints reflected in the UI)
 * both read from here, so the enforced policy and the displayed affordances can never drift.
 *
 * <p>Policy summary:</p>
 * <ul>
 *   <li><b>owner</b> — everything, including irreversible organization lifecycle and billing.</li>
 *   <li><b>admin</b> — manage members, organization content/settings, and which capabilities the org
 *       has turned on, but not owner-only lifecycle (delete/transfer) or billing.</li>
 *   <li><b>member</b> — view + manage organization content (create/curate), but no member,
 *       capability or billing management.</li>
 *   <li><b>viewer</b> — read-only; can view but mutate nothing.</li>
 *   <li><b>billing</b> — billing only; no access to organization content at all (a finance
 *       contact who should never see eval data).</li>
 * </ul>
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
        // Defensive: a freshly added role with no explicit entry grants nothing.
        for (Role r : Role.values()) {
            m.putIfAbsent(r, EnumSet.noneOf(Permission.class));
        }
        return m;
    }

    /** True if the given role holds the given permission. */
    public static boolean allows(Role role, Permission permission) {
        return MATRIX.getOrDefault(role, Set.of()).contains(permission);
    }

    /** The immutable set of permissions a role holds — useful for UI affordance hints. */
    public static Set<Permission> permissionsFor(Role role) {
        return Collections.unmodifiableSet(MATRIX.getOrDefault(role, EnumSet.noneOf(Permission.class)));
    }
}
