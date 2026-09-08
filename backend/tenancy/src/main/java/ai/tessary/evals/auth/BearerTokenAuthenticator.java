// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.auth;

import ai.tessary.evals.tenant.ApiKey;
import ai.tessary.evals.tenant.ApiKeyService;
import ai.tessary.evals.tenant.Project;
import ai.tessary.evals.tenant.ProjectRepository;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for {@code Authorization: Bearer <token>} → {@link TenantContext} resolution.
 *
 * <p>Parses the header value, verifies the presented MCP token ({@link ApiKeyService#verify}), resolves
 * its project ({@link ProjectRepository#findById}), and builds a project-bound {@link TenantContext} (role
 * {@code member}, as MCP tokens always act as a member). Extracted so every transport that authenticates a
 * bearer token resolves it <em>identically</em>: the servlet {@link AuthFilter} (cookies → bearer fallback
 * for {@code /mcp} and {@code /api/**}) and the OTLP gRPC receiver's {@code ServerInterceptor}, which
 * is not a servlet request and so cannot reuse the filter. Keeping one resolver prevents the two paths from
 * diverging on token semantics (orphaned-project handling, the hardcoded role, the MCP-token requirement).
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
     * Resolve a raw {@code Authorization} header value to a project-bound context.
     *
     * @param authorizationHeader the full header value (e.g. {@code "Bearer tsy_..."}), or {@code null}
     * @return the resolved context, or empty when the header is absent/not a bearer token, the token fails
     *     verification, or its project does not exist (an orphaned token)
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
