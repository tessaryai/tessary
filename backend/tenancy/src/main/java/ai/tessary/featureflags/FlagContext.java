// SPDX-License-Identifier: Apache-2.0
package ai.tessary.featureflags;

import org.jspecify.annotations.Nullable;

/**
 * A flag evaluation's scope: global or one org, never finer. Per-user or per-project capabilities would let two members
 * of one org disagree about what the product is. @param orgId the org ULID (never a slug), or {@code null} for global
 */
public record FlagContext(@Nullable String orgId) {

    /** Only a global default or rule can apply. */
    public static FlagContext global() {
        return new FlagContext(null);
    }

    public static FlagContext forOrg(@Nullable String orgId) {
        return new FlagContext(orgId);
    }
}
