// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.tenant.rbac.Permission;
import ai.tessary.evals.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manage MCP personal access tokens for a project. Gated exactly as the managed API keys on the same
 * table are ({@link ApiKeyController}): {@code ORG_VIEW} to list, {@code ORG_MANAGE} to issue or revoke.
 * A {@code member} still mints their own token to wire up Claude on their machine, which is what this
 * surface is for; a {@code viewer} or a {@code billing} principal cannot. Two separate facts make that
 * matter: the token is minted {@link KeyScope#ADMIN}, which is what lets it reach every REST surface
 * plus {@code /mcp}, and {@code BearerTokenAuthenticator} resolves every bearer key back as role
 * {@code member} regardless of who holds it. Without the permission check below, a caller could
 * therefore mint themselves more role than they were given. The plaintext token is
 * returned exactly once at issue time and never stored.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/mcp-tokens")
public class McpTokenController {

    private final ApiKeyService tokens;
    private final ApiKeyRepository tokenRepo;
    private final TenantPathResolver resolver;

    public McpTokenController(ApiKeyService tokens, ApiKeyRepository tokenRepo, TenantPathResolver resolver) {
        this.tokens = tokens;
        this.tokenRepo = tokenRepo;
        this.resolver = resolver;
    }

    public record IssueRequest(@NotBlank String name) {}

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "view MCP tokens");
        List<ApiKey> rows = tokenRepo.findByProject(r.project().id(), true);
        // Strip hash from the wire view.
        return ApiResponse.ok(rows.stream().map(McpTokenController::toWire).toList());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> issue(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody IssueRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "issue an MCP token");
        if (ctx.isMcpToken()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MCP tokens cannot mint new tokens");
        }
        ApiKeyService.Issued issued = tokens.issue(r.project().id(), ctx.userId(), req.name());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", toWire(issued.token()));
        body.put("plaintext", issued.plaintext());
        body.put("warning", "store this token now; it cannot be retrieved later");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    @DeleteMapping("/{tokenId}")
    public ApiResponse<Map<String, Object>> revoke(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String tokenId) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "revoke an MCP token");
        ApiKey row = tokenRepo
                .findById(tokenId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "token not found"));
        if (!r.project().id().equals(row.projectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "token not found");
        }
        boolean revoked = tokens.revoke(tokenId);
        return ApiResponse.ok(Map.of("revoked", revoked));
    }

    private static Map<String, Object> toWire(ApiKey t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("name", t.name());
        m.put("token_prefix", t.tokenPrefix());
        m.put("created_at", t.createdAt());
        m.put("last_used_at", t.lastUsedAt());
        m.put("revoked_at", t.revokedAt());
        m.put("principal_id", t.principalId());
        return m;
    }
}
