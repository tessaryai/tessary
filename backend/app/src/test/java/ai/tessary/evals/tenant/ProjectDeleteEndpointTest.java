// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.auth.TenantContext;
import ai.tessary.evals.auth.TenantPathResolver;
import ai.tessary.evals.testsupport.TenantFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

/**
 * What the DELETE endpoint promises now that it no longer does the deleting: it accepts, it revokes the
 * project's keys before returning, and it queues exactly one purge — and from that moment the project is
 * unreachable through every project-scoped route.
 *
 * <p>The revocation is the one part that is deliberately NOT backgrounded, so it gets its own assertion:
 * queuing it behind the purge would leave a window, minutes wide, in which a deleted project still
 * accepts ingest.
 */
@SpringBootTest
class ProjectDeleteEndpointTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("evals.secret-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired
    OrganizationController controller;

    @Autowired
    TenantPathResolver resolver;

    @Autowired
    ProjectRepository projects;

    @Autowired
    ApiKeyService apiKeyService;

    @Autowired
    ApiKeyRepository apiKeys;

    @Autowired
    TenantService tenants;

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("DELETE marks the project, revokes its keys synchronously, and queues one purge")
    void acceptsAndQueues() {
        var f = TenantFixture.bootstrap(tenants, "delete-accept");
        var extra = tenants.createProject(f.org().id(), "delete-accept-target", null);
        apiKeyService.issue(extra.id(), f.user().id(), "ingest");
        apiKeyService.issue(extra.id(), f.user().id(), "mcp");
        TenantContext ctx = ctx(f);

        controller.deleteProject(ctx, f.org().slug(), extra.slug());

        assertTrue(projects.findById(extra.id()).orElseThrow().isDeleting(), "project should be marked");
        assertEquals(0, apiKeys.findByProject(extra.id(), false).size(), "every key should be revoked");
        assertEquals(1, purgeJobs(extra.id()), "exactly one purge job");
    }

    @Test
    @DisplayName("a second DELETE is idempotent and does not queue a second purge")
    void secondDeleteIsIdempotent() {
        var f = TenantFixture.bootstrap(tenants, "delete-twice");
        var extra = tenants.createProject(f.org().id(), "delete-twice-target", null);
        TenantContext ctx = ctx(f);

        controller.deleteProject(ctx, f.org().slug(), extra.slug());
        controller.deleteProject(ctx, f.org().slug(), extra.slug());

        assertEquals(1, purgeJobs(extra.id()), "a retry must not queue a second purge");
    }

    @Test
    @DisplayName("the default project still cannot be deleted")
    void defaultProjectIsStillProtected() {
        var f = TenantFixture.bootstrap(tenants, "delete-default");
        TenantContext ctx = ctx(f);
        var defaulted = projects.findDefaultForOrg(f.org().id()).orElseThrow();

        var e = assertThrows(
                ResponseStatusException.class,
                () -> controller.deleteProject(ctx, f.org().slug(), defaulted.slug()));
        assertEquals(HttpStatus.CONFLICT, HttpStatus.valueOf(e.getStatusCode().value()));
        assertFalse(projects.findById(defaulted.id()).orElseThrow().isDeleting());
    }

    @Test
    @DisplayName("once accepted, the project resolves as not found for every project-scoped route")
    void deletingProjectIsUnreachable() {
        var f = TenantFixture.bootstrap(tenants, "delete-gate");
        var extra = tenants.createProject(f.org().id(), "delete-gate-target", null);
        TenantContext ctx = ctx(f);

        controller.deleteProject(ctx, f.org().slug(), extra.slug());

        var e = assertThrows(
                ResponseStatusException.class,
                () -> resolver.requireProject(ctx, f.org().slug(), extra.slug()));
        assertEquals(HttpStatus.NOT_FOUND, HttpStatus.valueOf(e.getStatusCode().value()));
        assertThrows(ResponseStatusException.class, () -> resolver.requireMembershipForProject(ctx, extra.id()));

        // …but the org's project list still carries it, which is what lets settings render `deleting`.
        assertTrue(controller.listProjects(ctx, f.org().slug()).data().stream()
                .anyMatch(p -> p.id().equals(extra.id()) && p.isDeleting()));
    }

    private TenantContext ctx(TenantFixture.Setup f) {
        return new TenantContext(f.user().id(), f.user().email(), null, null, null, null);
    }

    private int purgeJobs(String projectId) {
        return jdbc.sql("SELECT count(*) FROM job WHERE kind = 'project_delete' AND dedupe_key = :pid")
                .param("pid", projectId)
                .query(Integer.class)
                .single();
    }
}
