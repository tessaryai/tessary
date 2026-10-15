// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import ai.tessary.tenant.ApiKey;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code Authorization: Bearer <token>} to a project-bound {@link TenantContext} (role {@code member}). Shared
 * by {@link AuthFilter} and the OTLP gRPC interceptor, which is not a servlet request, so both paths agree on token
 * semantics.
 */
@Component
public class BearerTokenAuthenticator {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyService mcpTokens;
    private final ProjectRepository projects;

    public BearerTokenAuthenticator(ApiKeyService mcpTokens, ProjectRepository projects) {
        this.mcpTokens = mcpTokens;
        this.projects = projects;
    }

    /**
     * @return empty when the header is absent or not a bearer token, the token fails verification, or its project no
     * longer exists
     */
    public Optional<TenantContext> authenticate(@Nullable String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String presented = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        Optional<ApiKey> verified = mcpTokens.verify(presented);
        if (verified.isEmpty()) return Optional.empty();
        ApiKey t = verified.get();
        Optional<Project> proj = projects.findById(t.projectId());
        if (proj.isEmpty()) return Optional.empty(); // project deleted; token orphaned
        return Optional.of(new TenantContext(
                t.principalId(),
                null,
                proj.get().orgId(),
                t.projectId(),
                "member", // bearer keys always act as member
                t.id(),
                t.scopeEnum())); // least-privilege key family (write/query/mcp)
    }
}
