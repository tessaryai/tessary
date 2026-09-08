// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant.rbac;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The org-level roles a member can hold — rich enough that an enterprise account can
 * separate organization administration, read-only access, and billing from full ownership.
 *
 * <p>The wire/DB representation is the lowercase {@link #wire()} string (e.g. {@code "viewer"});
 * the {@code org_membership.role} / {@code org_invitation.role} CHECK constraints
 * pin the column to exactly these values. The permission each role grants lives in
 * {@link RolePermissions}, not here, so the role list and the policy stay decoupled.</p>
 */
public enum Role {
    /** Full control, including deleting the organization and managing every other role. The org always keeps >=1. */
    OWNER("owner"),
    /** Organization administration — manage members/roles, projects, settings — but not billing or owner-only actions. */
    ADMIN("admin"),
    /** Default collaborator: can create and curate content, but cannot manage members, billing, or the organization. */
    MEMBER("member"),
    /** Read-only: can see everything but mutate nothing. */
    VIEWER("viewer"),
    /** Billing-only: can reach billing/usage, but has no access to organization content management. */
    BILLING("billing");

    private final String wire;

    Role(String wire) {
        this.wire = wire;
    }

    /** The canonical lowercase string stored in the DB and sent over the wire. */
    public String wire() {
        return wire;
    }

    /** Parse a wire string into a {@link Role}, tolerating case/whitespace; empty if unknown. */
    public static Optional<Role> fromWire(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(r -> r.wire.equals(normalized)).findFirst();
    }

    /**
     * Parse a wire string, falling back to {@link #MEMBER} for null/blank/unknown values.
     * Used when reading a persisted role where a missing value should degrade safely to the
     * least-privileged collaborator role rather than throwing.
     */
    public static Role fromWireOrMember(String value) {
        return fromWire(value).orElse(MEMBER);
    }
}
