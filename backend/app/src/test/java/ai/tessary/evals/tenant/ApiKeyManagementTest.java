// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.testsupport.TenantFixture;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Managed-key lifecycle against the real Postgres (Testcontainers): create → hash → resolve →
 * rotate → revoke, the scope dimension, and the audit trail. Complements
 * {@link ApiKeyServiceTest}, which covers the bcrypt/prefix verification surface.
 */
@SpringBootTest
class ApiKeyManagementTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    TenantService tenants;

    @Autowired
    ApiKeyService keys;

    @Autowired
    ApiKeyRepository repo;

    @Autowired
    AuditLogRepository audit;

    @Test
    void create_persistsScope_andResolvesIt() {
        var fix = TenantFixture.bootstrap(tenants, "key-scope");

        var issued = keys.issue(fix.project().id(), fix.user().id(), "prod-write", KeyScope.WRITE);

        assertEquals(KeyScope.WRITE.wire(), issued.token().scope());

        // Verifying the plaintext returns the same scope binding.
        Optional<ApiKey> resolved = keys.verify(issued.plaintext());
        assertTrue(resolved.isPresent());
        assertEquals(KeyScope.WRITE, resolved.get().scopeEnum());
    }

    @Test
    void legacyIssue_defaultsToMcpScope() {
        var fix = TenantFixture.bootstrap(tenants, "key-legacy");
        var issued = keys.issue(fix.project().id(), fix.user().id(), "laptop");
        assertEquals(KeyScope.ADMIN, issued.token().scopeEnum());
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
    void lifecycleActions_writeAuditRows() {
        var fix = TenantFixture.bootstrap(tenants, "key-audit");
        var issued = keys.issue(fix.project().id(), fix.user().id(), "audited", KeyScope.ADMIN);
        keys.rotate(issued.token().id(), fix.user().id());

        List<AuditLog> rows = audit.findByProject(fix.project().id(), 50);
        // create + (rotate => revoke-old + created-new + rotated) — at minimum a created and a rotated entry.
        assertTrue(rows.stream().anyMatch(a -> AuditLog.Action.CREATED.wire().equals(a.action())));
        assertTrue(rows.stream().anyMatch(a -> AuditLog.Action.ROTATED.wire().equals(a.action())));
        assertTrue(
                rows.stream().anyMatch(a -> AuditLog.Action.REVOKED.wire().equals(a.action())),
                "rotate revokes the old key, which is audited");
        assertTrue(rows.stream().allMatch(a -> fix.user().id().equals(a.principalId())), "actor is recorded");
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
}
