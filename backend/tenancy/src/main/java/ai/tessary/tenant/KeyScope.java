// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import java.util.Locale;
import java.util.Optional;

/**
 * The managed API-key families; each project-scoped key carries exactly one. {@link #ADMIN} satisfies every surface and
 * grants {@code /mcp}, and is never the issue-time default. The wire form is the lowercase name, as stored in
 * {@code api_key.scope}.
 */
public enum KeyScope {
    WRITE,
    QUERY,
    ADMIN;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The token prefix letter ({@code tsy_w_} / {@code tsy_q_} / {@code tsy_a_}) is a human signal; the DB
     * {@code scope} column stays authoritative.
     */
    public String letter() {
        return switch (this) {
            case WRITE -> "w";
            case QUERY -> "q";
            case ADMIN -> "a";
        };
    }

    /** Defaults to the least-privilege {@link #WRITE}, never the read-capable superset. */
    public static KeyScope fromWireOrDefault(String wire) {
        return parse(wire).orElse(WRITE);
    }

    /** Empty for an unknown family. */
    public static Optional<KeyScope> parse(String wire) {
        if (wire == null || wire.isBlank()) return Optional.empty();
        try {
            return Optional.of(KeyScope.valueOf(wire.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** ADMIN satisfies every surface; WRITE and QUERY only their own. */
    public boolean permits(KeyScope required) {
        return this == required || this == ADMIN;
    }
}
