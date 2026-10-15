// SPDX-License-Identifier: Apache-2.0
package ai.tessary.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.tenant.Organization;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tenant resolver is the only layer enforcing cross-org isolation. Every
 * REST controller funnels through it; one missed check means a user with org
 * Acme can see org Globex by guessing the slug.
 *
 * <p>Coverage focuses on the negative paths (404 / 403) plus the special
 * MCP-token rule: a token bound to project A must be rejected by any URL
 * that doesn't resolve to project A.</p>
 */
@SpringBootTest
record TenantPathResolverTest(TenantPathResolver resolver, TenantService tenants) {

    private TenantContext sessionFor(Principal u) {
        return new TenantContext(u.id(), u.email(), null, null, null, null);
    }

    private TenantContext mcpTokenFor(Organization org, Project project, Principal u) {
        return new TenantContext(u.id(), null, org.id(), project.id(), "member", "tok_" + project.id());
    }

    @Test
    void requireProject_withinOwnOrg_ok() {
        var fix = TenantFixture.bootstrap(tenants, "pr-happy");
        var r = resolver.requireProject(
                sessionFor(fix.user()), fix.org().slug(), fix.project().slug());
        assertEquals(fix.project().id(), r.project().id());
        assertEquals("owner", r.role(), "owner of personal org keeps owner on project resolve");
    }

    @Test
    void requireProject_projectInOtherOrg_404() {
        var alice = TenantFixture.bootstrap(tenants, "pr-cross-alice");
        var bob = TenantFixture.bootstrap(tenants, "pr-cross-bob");
        // Alice's project slug under Bob's own org: the slug must be looked up within Bob's org only.
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> resolver.requireProject(sessionFor(bob.user()), bob.org().slug(), alice.project().slug()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void mcpToken_boundProject_allowsExactlyOwnOrgAndProject() {
        var fix = TenantFixture.bootstrap(tenants, "pr-mcp-ok");
        TenantContext ctx = mcpTokenFor(fix.org(), fix.project(), fix.user());

        // Same org + same project → ok
        var r = resolver.requireProject(ctx, fix.org().slug(), fix.project().slug());
        assertEquals(fix.project().id(), r.project().id());
    }

    @Test
    void mcpToken_rejectsDifferentOrg() {
        var alice = TenantFixture.bootstrap(tenants, "pr-mcp-x-alice");
        var bob = TenantFixture.bootstrap(tenants, "pr-mcp-x-bob");
        TenantContext aliceToken = mcpTokenFor(alice.org(), alice.project(), alice.user());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> resolver.requireOrg(aliceToken, bob.org().slug()));
        assertEquals(
                HttpStatus.FORBIDDEN,
                ex.getStatusCode(),
                "MCP token bound to alice's org must be 403 against bob's org");
    }

    @Test
    void mcpToken_rejectsDifferentProjectWithinSameOrg() {
        // Same user, two projects under the same org.
        var fix1 = TenantFixture.bootstrap(tenants, "pr-mcp-two");
        Project second = tenants.createProject(fix1.org().id(), "another", null);

        TenantContext tokenForFirst = mcpTokenFor(fix1.org(), fix1.project(), fix1.user());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> resolver.requireProject(tokenForFirst, fix1.org().slug(), second.slug()));
        assertEquals(
                HttpStatus.FORBIDDEN, ex.getStatusCode(), "token bound to project A must not satisfy project B's path");
    }
}
