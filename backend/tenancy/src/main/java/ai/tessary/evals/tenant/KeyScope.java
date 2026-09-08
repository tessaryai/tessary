// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

import java.util.Locale;
import java.util.Optional;

/**
 * The three managed API-key families. Each project-scoped bearer key carries exactly one
 * scope, which least-privileges the surfaces it may reach:
 *
 * <ul>
 *   <li>{@link #WRITE} — the ingest front door ({@code POST /v1/traces}).</li>
 *   <li>{@link #QUERY} — the aggregation-first read API ({@code POST /v1/query/*}).</li>
 *   <li>{@link #ADMIN} — the broadest family: it additionally satisfies the write and query surfaces and
 *       grants tool access over {@code /mcp}, so a single {@code tsy_a_} key can do everything server-side
 *       when an app wants one token rather than least-privilege ones. Never the issue-time
 *       default.</li>
 * </ul>
 *
 * <p>The wire form is the lowercase enum name ({@code write} / {@code query} / {@code admin}); it is what
 * the {@code api_key.scope} column stores and what the management API accepts. The token's literal
 * prefix encodes the same as a single letter ({@code tsy_w_} / {@code tsy_q_} / {@code tsy_a_}).</p>
 */
public enum KeyScope {
    WRITE,
    QUERY,
    ADMIN;

    /** The value persisted in {@code api_key.scope} and exchanged on the wire. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The single-letter code embedded in a token's prefix ({@code tsy_<letter>_…}) as a human-readable
     * scope signal — {@code w} / {@code q} / {@code a}. The DB {@code scope} column stays authoritative. */
    public String letter() {
        return switch (this) {
            case WRITE -> "w";
            case QUERY -> "q";
            case ADMIN -> "a";
        };
    }

    /** Parse a wire value, defaulting to the least-privilege {@link #WRITE} for null/blank. Tokens are
     * issued with an explicit scope, so the default only guards a stray blank —
     * and it must NOT be the read-capable superset. */
    public static KeyScope fromWireOrDefault(String wire) {
        return parse(wire).orElse(WRITE);
    }

    /** Strict parse used by request validation; empty for an unknown family. */
    public static Optional<KeyScope> parse(String wire) {
        if (wire == null || wire.isBlank()) return Optional.empty();
        try {
            return Optional.of(KeyScope.valueOf(wire.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Whether a key of this family may act on the surface that {@code required} guards. ADMIN is the
     * superset family (satisfies every surface); WRITE and QUERY are strictly least-privilege and only
     * satisfy their own surface.
     */
    public boolean permits(KeyScope required) {
        return this == required || this == ADMIN;
    }
}
