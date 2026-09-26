// SPDX-License-Identifier: Apache-2.0
package ai.tessary.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.testsupport.TenantFixture;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * Managed-key lifecycle against the real Postgres (Testcontainers): create → hash → resolve →
 * rotate → revoke, the scope dimension, and the audit trail. Complements
 * {@link ApiKeyServiceTest}, which covers the bcrypt/prefix verification surface.
 */
@SpringBootTest
class ApiKeyManagementTest {

    @Autowired
    TenantService tenants;

    @Autowired
    ApiKeyService keys;

    @Autowired
    ApiKeyRepository repo;

    @Autowired
    AuditLogRepository audit;

    @Autowired
    ApiKeyController keyController;

    @Autowired
    McpTokenController mcpController;

    private static TenantContext session(Principal u) {
        return new TenantContext(u.id(), u.email(), null, null, null, null);
    }

    /** A bearer-token context bound to {@code fix}'s project, the shape {@code BearerTokenAuthenticator} builds. */
    private static TenantContext tokenContext(TenantFixture.Setup fix) {
        return new TenantContext(
                fix.user().id(), null, fix.org().id(), fix.project().id(), "member", "tok-1", KeyScope.ADMIN);
    }

    private static HttpStatusCode statusOf(Executable call) {
        return assertThrows(ResponseStatusException.class, call).getStatusCode();
    }

    /** The wire view a key row must be listed as: every public column, and never the hash. */
    private static Map<String, Object> wire(ApiKey k, boolean withScope) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.id());
        m.put("name", k.name());
        if (withScope) m.put("scope", k.scope());
        m.put("token_prefix", k.tokenPrefix());
        m.put("created_at", k.createdAt());
        m.put("last_used_at", k.lastUsedAt());
        m.put("revoked_at", k.revokedAt());
        m.put("principal_id", k.principalId());
        return m;
    }

    @Test
    void rotate_revokesOld_issuesFreshWithSameScope() {
        var fix = TenantFixture.bootstrap(tenants, "key-rotate");
        var original = keys.issue(fix.project().id(), fix.user().id(), "ci", KeyScope.QUERY);

        assertTrue(keys.verify(original.plaintext()).isPresent(), "live key verifies before rotate");

        Optional<ApiKeyService.Issued> rotated =
                keys.rotate(original.token().id(), fix.user().id());
        assertTrue(rotated.isPresent());
        assertNotNull(rotated.get().plaintext());
        assertNotEquals(original.plaintext(), rotated.get().plaintext(), "rotate mints a new secret");

        // Old secret stops working; new secret works and keeps scope + env.
        assertTrue(keys.verify(original.plaintext()).isEmpty(), "rotated-away key must fail verification");
        Optional<ApiKey> fresh = keys.verify(rotated.get().plaintext());
        assertTrue(fresh.isPresent());
        assertEquals(KeyScope.QUERY, fresh.get().scopeEnum());

        // Old row is revoked; rotating it again is a no-op.
        assertTrue(repo.findById(original.token().id()).orElseThrow().isRevoked());
        assertTrue(keys.rotate(original.token().id(), fix.user().id()).isEmpty(), "cannot rotate a revoked key");
    }

    @Test
    void revoke_writesAuditAndIsIdempotent() {
        var fix = TenantFixture.bootstrap(tenants, "key-revoke-audit");
        var issued = keys.issue(fix.project().id(), fix.user().id(), "to-revoke", KeyScope.WRITE);

        assertTrue(keys.revoke(issued.token().id(), fix.user().id()));
        assertFalse(keys.revoke(issued.token().id(), fix.user().id()), "second revoke is a no-op (no new audit)");

        long revokedAudits = audit.findByProject(fix.project().id(), 50).stream()
                .filter(a -> AuditLog.Action.REVOKED.wire().equals(a.action()))
                .count();
        assertEquals(1, revokedAudits, "exactly one revoke audit row for one effective revoke");
    }

    @Test
    void keyScopePermissions_leastPrivilege() {
        // A write key may ingest (WRITE) but not read (QUERY) or drive MCP.
        assertTrue(KeyScope.WRITE.permits(KeyScope.WRITE));
        assertFalse(KeyScope.WRITE.permits(KeyScope.QUERY));
        assertFalse(KeyScope.WRITE.permits(KeyScope.ADMIN));
        // A query key may read but not write.
        assertTrue(KeyScope.QUERY.permits(KeyScope.QUERY));
        assertFalse(KeyScope.QUERY.permits(KeyScope.WRITE));
        // MCP is the backwards-compatible superset family.
        assertTrue(KeyScope.ADMIN.permits(KeyScope.ADMIN));
        assertTrue(KeyScope.ADMIN.permits(KeyScope.WRITE));
        assertTrue(KeyScope.ADMIN.permits(KeyScope.QUERY));
    }

    /**
     * The bugs: the minted secret is not the one that verifies, or verifies under another scope; a listed
     * key leaks its hash or drops a column; a rotate does not retire the old key; or a revoked key can be
     * rotated back to life.
     */
    @Test
    void keyController_mintsListsRotatesAndRevokesThroughTheWireView() {
        var fix = TenantFixture.bootstrap(tenants, "key-controller");
        TenantContext owner = session(fix.user());
        String org = fix.org().slug();
        String project = fix.project().slug();

        var created = keyController.create(owner, org, project, new ApiKeyController.CreateRequest("ci", "query"));
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> key = (Map<String, Object>) created.getBody().data().get("key");
        ApiKey row = repo.findById((String) key.get("id")).orElseThrow();
        assertEquals(wire(row, true), key);
        assertEquals(
                List.of(wire(row, true)),
                keyController.list(owner, org, project).data());
        assertEquals(
                Optional.of(KeyScope.QUERY),
                keys.verify((String) created.getBody().data().get("plaintext")).map(ApiKey::scopeEnum));

        var rotated = keyController.rotate(owner, org, project, row.id());
        assertEquals(HttpStatus.CREATED, rotated.getStatusCode());
        @SuppressWarnings("unchecked")
        String freshId =
                (String) ((Map<String, Object>) rotated.getBody().data().get("key")).get("id");
        assertTrue(repo.findById(row.id()).orElseThrow().isRevoked(), "rotating retires the old key");
        assertEquals(HttpStatus.CONFLICT, statusOf(() -> keyController.rotate(owner, org, project, row.id())));

        assertEquals(
                Map.of("revoked", true),
                keyController.revoke(owner, org, project, freshId).data());
        assertEquals(
                Set.of(AuditLog.Action.CREATED.wire(), AuditLog.Action.ROTATED.wire(), AuditLog.Action.REVOKED.wire()),
                keyController.audit(owner, org, project).data().stream()
                        .map(AuditLog::action)
                        .collect(Collectors.toSet()));
    }

    /**
     * The bugs: an unknown scope is minted as some default family, a bearer token mints itself a new key or
     * token, or a key of another project can be rotated or revoked through this project's URL.
     */
    @Test
    void keyAndTokenControllers_refuseUnknownScopesTokenMintingAndForeignKeys() {
        var fix = TenantFixture.bootstrap(tenants, "key-refusals");
        var other = TenantFixture.bootstrap(tenants, "key-refusals-other");
        TenantContext owner = session(fix.user());
        String org = fix.org().slug();
        String project = fix.project().slug();
        String foreignKey = keys.issue(other.project().id(), other.user().id(), "theirs", KeyScope.WRITE)
                .token()
                .id();

        assertEquals(
                HttpStatus.BAD_REQUEST,
                statusOf(() ->
                        keyController.create(owner, org, project, new ApiKeyController.CreateRequest("x", "root"))));
        assertEquals(
                HttpStatus.FORBIDDEN,
                statusOf(() -> keyController.create(
                        tokenContext(fix), org, project, new ApiKeyController.CreateRequest("x", "write"))));
        assertEquals(
                HttpStatus.FORBIDDEN,
                statusOf(() -> mcpController.issue(
                        tokenContext(fix), org, project, new McpTokenController.IssueRequest("x"))));
        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> keyController.rotate(owner, org, project, foreignKey)));
        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> keyController.revoke(owner, org, project, foreignKey)));
        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> keyController.revoke(owner, org, project, "no-such-key")));
        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> mcpController.revoke(owner, org, project, foreignKey)));
        assertEquals(HttpStatus.NOT_FOUND, statusOf(() -> mcpController.revoke(owner, org, project, "no-such-token")));
        assertFalse(repo.findById(foreignKey).orElseThrow().isRevoked(), "the other project's key is untouched");
    }

    /**
     * The bugs: an issued MCP token is not the admin family that reaches {@code /mcp}, its listing leaks the
     * hash, or revoking it through the controller leaves it verifying.
     */
    @Test
    void mcpTokenController_issuesListsAndRevokes() {
        var fix = TenantFixture.bootstrap(tenants, "mcp-controller");
        TenantContext owner = session(fix.user());
        String org = fix.org().slug();
        String project = fix.project().slug();

        var issued = mcpController.issue(owner, org, project, new McpTokenController.IssueRequest("laptop"));
        assertEquals(HttpStatus.CREATED, issued.getStatusCode());
        String plaintext = (String) issued.getBody().data().get("plaintext");
        ApiKey row = keys.verify(plaintext).orElseThrow();
        assertEquals(KeyScope.ADMIN, row.scopeEnum());
        assertEquals(wire(row, false), issued.getBody().data().get("token"));
        assertEquals(
                List.of(wire(repo.findById(row.id()).orElseThrow(), false)),
                mcpController.list(owner, org, project).data());

        assertEquals(
                Map.of("revoked", true),
                mcpController.revoke(owner, org, project, row.id()).data());
        assertTrue(keys.verify(plaintext).isEmpty(), "a revoked token stops verifying");
    }
}
