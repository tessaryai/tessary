// SPDX-License-Identifier: Apache-2.0
package ai.tessary.featureflags;

import ai.tessary.auth.TenantContext;
import org.jspecify.annotations.Nullable;

/**
 * The targeting scope of a flag evaluation. There are exactly two: <b>global</b> (no org) and <b>one org</b>.
 * Nothing finer — no project, no user.
 *
 * <p>That is a product decision, not an omission. A capability is something an organization has or doesn't;
 * making it per-user or per-project would mean two members of the same org disagreeing about what the product
 * is, and it would put targeting rules where nobody can find them. The per-project switch that does exist (a
 * classifier's {@code enabled} column) is a different question — "this project has it turned on" — and the org
 * flag wins over it.
 *
 * @param orgId the org ULID in scope (never a slug), or {@code null} for a global evaluation.
 */
public record FlagContext(@Nullable String orgId) {

    /** No org in scope: only a flag's global default (or a global rule) can apply. */
    public static FlagContext global() {
        return new FlagContext(null);
    }

    /** Target one org by ULID. */
    public static FlagContext forOrg(@Nullable String orgId) {
        return new FlagContext(orgId);
    }

    /** Build from a controller's resolved {@link TenantContext} — its org, ignoring user and project. */
    public static FlagContext of(TenantContext ctx) {
        return new FlagContext(ctx.orgId());
    }
}
