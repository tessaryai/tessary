// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import ai.tessary.auth.TenantContext;
import ai.tessary.auth.TenantPathResolver;
import ai.tessary.tenant.rbac.Permission;
import ai.tessary.web.ApiResponse;
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
 * Create, rotate and revoke API keys in all three families. Listing needs {@link Permission#ORG_VIEW}; changes need
 * {@link Permission#ORG_MANAGE}, so a viewer cannot mint a billable key. The plaintext is returned once and never
 * stored.
 */
@RestController
@RequestMapping("/api/orgs/{orgSlug}/projects/{projectSlug}/api-keys")
public class ApiKeyController {

    private static final int AUDIT_LIMIT = 200;

    private final ApiKeyService keys;
    private final ApiKeyRepository keyRepo;
    private final AuditLogRepository auditRepo;
    private final TenantPathResolver resolver;

    public ApiKeyController(
            ApiKeyService keys, ApiKeyRepository keyRepo, AuditLogRepository auditRepo, TenantPathResolver resolver) {
        this.keys = keys;
        this.keyRepo = keyRepo;
        this.auditRepo = auditRepo;
        this.resolver = resolver;
    }

    public record CreateRequest(
            @NotBlank String name, @NotBlank String scope) {}

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "view API keys");
        List<ApiKey> rows = keyRepo.findByProject(r.project().id());
        return ApiResponse.ok(rows.stream().map(ApiKeyController::toWire).toList());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @Valid @RequestBody CreateRequest req) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "create an API key");
        if (ctx.isMcpToken()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "API keys cannot mint new keys");
        }
        KeyScope scope = KeyScope.parse(req.scope())
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown key scope '" + req.scope() + "'"));
        ApiKeyService.Issued issued = keys.issue(r.project().id(), ctx.userId(), req.name(), scope);
        return created(issued);
    }

    @PostMapping("/{keyId}/rotate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rotate(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String keyId) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "rotate an API key");
        ApiKey row = requireKeyInProject(keyId, r.project().id());
        if (row.isRevoked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "cannot rotate a revoked key");
        }
        ApiKeyService.Issued issued = keys.rotate(keyId, ctx.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "key could not be rotated"));
        return created(issued);
    }

    @DeleteMapping("/{keyId}")
    public ApiResponse<Map<String, Object>> revoke(
            TenantContext ctx,
            @PathVariable String orgSlug,
            @PathVariable String projectSlug,
            @PathVariable String keyId) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_MANAGE, "revoke an API key");
        requireKeyInProject(keyId, r.project().id());
        boolean revoked = keys.revoke(keyId, ctx.userId());
        return ApiResponse.ok(Map.of("revoked", revoked));
    }

    /** Newest first. */
    @GetMapping("/audit")
    public ApiResponse<List<AuditLog>> audit(
            TenantContext ctx, @PathVariable String orgSlug, @PathVariable String projectSlug) {
        var r = resolver.requireProject(ctx, orgSlug, projectSlug);
        r.require(Permission.ORG_VIEW, "view API-key audit");
        return ApiResponse.ok(auditRepo.findByProject(r.project().id(), AUDIT_LIMIT));
    }

    private ApiKey requireKeyInProject(String keyId, String projectId) {
        ApiKey row = keyRepo.findById(keyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "key not found"));
        if (!projectId.equals(row.projectId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "key not found");
        }
        return row;
    }

    private static ResponseEntity<ApiResponse<Map<String, Object>>> created(ApiKeyService.Issued issued) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", toWire(issued.token()));
        body.put("plaintext", issued.plaintext());
        body.put("warning", "store this key now; it cannot be retrieved later");
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }

    private static Map<String, Object> toWire(ApiKey t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("name", t.name());
        m.put("scope", t.scope());
        m.put("token_prefix", t.tokenPrefix());
        m.put("created_at", t.createdAt());
        m.put("last_used_at", t.lastUsedAt());
        m.put("revoked_at", t.revokedAt());
        m.put("principal_id", t.principalId());
        return m;
    }
}
