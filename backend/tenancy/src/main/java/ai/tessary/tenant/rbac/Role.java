// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant.rbac;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Org-level roles. {@link #wire()} is pinned by the {@code org_membership.role} and {@code org_invitation.role} CHECKs;
 * permissions live in {@link RolePermissions}.
 */
public enum Role {
    /** The org always keeps at least one. */
    OWNER("owner"),
    /** No billing or owner-only actions. */
    ADMIN("admin"),
    /** Cannot manage members, billing or the organization. */
    MEMBER("member"),
    /** Read-only: can see everything but mutate nothing. */
    VIEWER("viewer"),
    /** Billing-only: can reach billing/usage, but has no access to organization content management. */
    BILLING("billing");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** Tolerates case and whitespace; empty if unknown. */
    public static Optional<Role> fromWire(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(r -> r.wire.equals(normalized)).findFirst();
    }

    /** Unknown degrades to the least-privileged collaborator role rather than throwing. */
    public static Role fromWireOrMember(String value) {
        return fromWire(value).orElse(MEMBER);
    }
}
