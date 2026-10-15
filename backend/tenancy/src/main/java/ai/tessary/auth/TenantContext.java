// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import ai.tessary.tenant.KeyScope;
import org.jspecify.annotations.Nullable;

/**
 * Per-request authentication and tenancy, from {@link AuthFilter}. A user session has {@code userId} and resolves org
 * and project from the path; an API key context has {@code projectId}, {@code orgId}, role {@code "member"}, a non-null
 * {@code mcpTokenId} and a {@code keyScope}.
 */
public record TenantContext(
        String userId,
        @Nullable String userEmail,
        @Nullable String orgId,
        @Nullable String projectId,
        @Nullable String role,
        @Nullable String mcpTokenId,
        @Nullable KeyScope keyScope) {
    public static final String ATTRIBUTE = "tessary.tenant";

    /** For non-key shapes; {@code keyScope} is null. */
    public TenantContext(
            String userId,
            @Nullable String userEmail,
            @Nullable String orgId,
            @Nullable String projectId,
            @Nullable String role,
            @Nullable String mcpTokenId) {
        this(userId, userEmail, orgId, projectId, role, mcpTokenId, null);
    }

    public boolean isAuthenticated() {
        return userId != null;
    }

    public boolean isMcpToken() {
        return mcpTokenId != null;
    }

    /** User sessions always pass; surface RBAC gates them elsewhere. */
    public boolean keyPermits(KeyScope required) {
        return keyScope == null || keyScope.permits(required);
    }
}
