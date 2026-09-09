// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import ai.tessary.tenant.KeyScope;
import org.jspecify.annotations.Nullable;

/**
 * Per-request authentication + tenancy. Populated by {@link AuthFilter} and
 * available to controllers via {@link TenantArgumentResolver}.
 *
 * <p>Two valid shapes:</p>
 * <ul>
 *   <li><b>User session</b> — {@code userId} set; {@code orgId} / {@code projectId} /
 *       {@code role} resolved later from the URL path. {@code mcpTokenId} is null.</li>
 *   <li><b>API key (bearer)</b> — {@code userId} is the key creator; {@code projectId} is
 *       set (key scope); {@code orgId} is set (the project's org); {@code role} is
 *       always {@code "member"}; {@code mcpTokenId} is non-null. {@code keyScope} carries the
 *       key family (write / query / mcp), and is null on user sessions.</li>
 * </ul>
 *
 * <p>{@code projectId} is null on user sessions until a controller helper resolves
 * it from the {orgSlug, projectSlug} path variables.</p>
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

    /**
     * Backwards-compatible constructor for the non-key shapes (user sessions) and any caller that does
     * not carry a key scope. Leaves {@code keyScope} null.
     */
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

    public boolean isOwner() {
        return "owner".equals(role);
    }

    /**
     * Whether this context (when it is a bearer-key context) is allowed to act on the surface that
     * {@code required} guards. User sessions are not key-scoped, so they always pass — surface-level RBAC
     * gates them elsewhere. A key context passes only when its family {@link KeyScope#permits} the
     * required family.
     */
    public boolean keyPermits(KeyScope required) {
        return keyScope == null || keyScope.permits(required);
    }

    public TenantContext withProject(String orgId, String projectId, String role) {
        return new TenantContext(userId, userEmail, orgId, projectId, role, mcpTokenId, keyScope);
    }
}
