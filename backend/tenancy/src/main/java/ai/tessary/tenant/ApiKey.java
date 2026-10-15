// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * A project-scoped {@code api_key} row in one {@link #scope} family. The hash never goes over the wire. {@code scopes},
 * {@code expiresAt} and {@code attributes} are reserved for finer-grained policy.
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

    /** A stray blank defaults to least-privilege {@link KeyScope#WRITE}, never the read-capable superset. */
    public KeyScope scopeEnum() {
        return KeyScope.fromWireOrDefault(scope);
    }

    /** Policy columns default to no scopes and no expiry. */
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
