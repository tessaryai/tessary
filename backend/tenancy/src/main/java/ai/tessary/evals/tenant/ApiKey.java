// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * A managed, project-scoped API key — the {@code api_key} table. A row belongs
 * to a {@link #scope} family (write / query / admin). The hash is never exposed over the wire; the
 * plaintext token is shown to the user exactly once at issue time.
 *
 * <p>{@code scopes} is a JSON permission-narrowing bag (distinct from the coarse {@code scope} family),
 * {@code expiresAt} an optional expiry, and {@code attributes} a free-form bag — all reserved for
 * finer-grained key policy.</p>
 */
public record ApiKey(
        String id,
        @JsonProperty("project_id") String projectId,
        @JsonProperty("principal_id") String principalId,
        String name,
        @JsonProperty("token_prefix") String tokenPrefix,
        @JsonProperty("token_hash") String tokenHash,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("last_used_at") @Nullable String lastUsedAt,
        @JsonProperty("revoked_at") @Nullable String revokedAt,
        String scope,
        @Nullable String scopes,
        @JsonProperty("expires_at") @Nullable String expiresAt,
        @Nullable String attributes) {

    public boolean isRevoked() {
        return revokedAt != null && !revokedAt.isBlank();
    }

    /** The key family this row belongs to (defaults to least-privilege {@link KeyScope#WRITE} for a
     * stray blank — never the read-capable superset). */
    public KeyScope scopeEnum() {
        return KeyScope.fromWireOrDefault(scope);
    }

    /** Build a key from the 10 core fields, defaulting the policy columns (no scopes/expiry). */
    public static ApiKey of(
            String id,
            String projectId,
            String principalId,
            String name,
            String tokenPrefix,
            String tokenHash,
            String createdAt,
            @Nullable String lastUsedAt,
            @Nullable String revokedAt,
            String scope) {
        return new ApiKey(
                id,
                projectId,
                principalId,
                name,
                tokenPrefix,
                tokenHash,
                createdAt,
                lastUsedAt,
                revokedAt,
                scope,
                "{}",
                null,
                "{}");
    }
}
