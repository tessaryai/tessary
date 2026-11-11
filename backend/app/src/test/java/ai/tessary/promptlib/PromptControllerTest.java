// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.tessary.auth.TenantContext;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.promptlib.PromptDtos.CreatePromptRequest;
import ai.tessary.promptlib.PromptDtos.CreateVersionRequest;
import ai.tessary.promptlib.PromptDtos.LabelRef;
import ai.tessary.promptlib.PromptDtos.PromptDetail;
import ai.tessary.promptlib.PromptDtos.PromptPage;
import ai.tessary.promptlib.PromptDtos.PromptVersionView;
import ai.tessary.promptlib.PromptDtos.SetLabelsRequest;
import ai.tessary.promptlib.PromptDtos.UpdateTagsRequest;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.TenantService;
import ai.tessary.tenant.rbac.Role;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Prompts page API through the controller, service and Postgres. The bugs: a viewer or an ingest key writing a
 * prompt, a query key writing, a promote that leaves the old version labeled, a stale {@code latest}, and a folder
 * delete or version delete that ignores its guard.
 */
@SpringBootTest
record PromptControllerTest(PromptController controller, TenantService tenants, OrgMembershipRepository memberships) {

    private static TenantContext owner(TenantFixture.Setup fix) {
        return new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
    }

    private static TenantContext key(TenantFixture.Setup fix, KeyScope scope) {
        return new TenantContext(
                fix.user().id(), null, fix.org().id(), fix.project().id(), "member", "key-1", scope);
    }

    private static CreatePromptRequest text(String name, @Nullable List<String> labels) {
        return new CreatePromptRequest(name, PromptType.TEXT, "Hello {{name}}", null, labels, null, null);
    }

    private TenantContext memberWithRole(TenantFixture.Setup fix, Role role) {
        long n = System.nanoTime();
        Principal p = tenants.upsertUserFromWorkos(
                "user_prompt_" + role.wire() + "_" + n, "prompt-" + role.wire() + "+" + n + "@example.com", "m", null);
        memberships.insert(OrgMembership.of(
                fix.org().id(), p.id(), role.wire(), Instant.now().toString()));
        return new TenantContext(p.id(), p.email(), null, null, null, null);
    }

    @Test
    void viewerCannotWrite_memberCan() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-ctl-rbac");
        String org = fix.org().slug();
        String proj = fix.project().slug();

        ResponseStatusException refused = assertThrows(
                ResponseStatusException.class,
                () -> controller.create(memberWithRole(fix, Role.VIEWER), org, proj, text("p", null)));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());

        PromptDetail created = requireNonNull(controller
                .create(memberWithRole(fix, Role.MEMBER), org, proj, text("p", null))
                .data());
        assertEquals(1, created.latestVersion());
    }

    @Test
    void writeKeyGetsNothing_queryKeyReadsOnly() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-ctl-scope");
        String org = fix.org().slug();
        String proj = fix.project().slug();

        assertError(
                PromptsError.WRONG_KEY_SCOPE,
                () -> controller.list(
                        key(fix, KeyScope.WRITE), org, proj, null, null, null, null, null, null, null, null));
        TenantContext query = key(fix, KeyScope.QUERY);
        assertEquals(
                0,
                requireNonNull(controller
                                .list(query, org, proj, null, null, null, null, null, null, null, null)
                                .data())
                        .total());
        assertError(PromptsError.WRONG_KEY_SCOPE, () -> controller.create(query, org, proj, text("p", null)));
        PromptDetail created = requireNonNull(controller
                .create(key(fix, KeyScope.ADMIN), org, proj, text("p", null))
                .data());
        assertEquals("p", created.name());
    }

    @Test
    void createThenVersionThenPromote() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-ctl-promote");
        TenantContext me = owner(fix);
        String org = fix.org().slug();
        String proj = fix.project().slug();

        String id = requireNonNull(controller
                        .create(me, org, proj, text("support/greeting", List.of("staging")))
                        .data())
                .id();
        PromptVersionView v2 = requireNonNull(controller
                .createVersion(me, org, proj, id, new CreateVersionRequest("Hi {{name}}", Map.of("t", 1), null, "two"))
                .data());
        assertEquals(2, v2.version());
        controller.setLabels(me, org, proj, id, 2, new SetLabelsRequest(List.of("production")));
        controller.setLabels(me, org, proj, id, 1, new SetLabelsRequest(List.of()));

        PromptDetail detail = requireNonNull(controller.get(me, org, proj, id).data());
        assertEquals(List.of(new LabelRef("production", 2)), detail.labels());
        assertEquals(List.of("production", "latest"), detail.versions().get(0).labels());
        assertEquals(List.of(), detail.versions().get(1).labels());
        PromptVersionView read =
                requireNonNull(controller.getVersion(me, org, proj, id, 2).data());
        assertEquals("Hi {{name}}", read.body());
        assertEquals(Map.of("t", 1), read.config());
        assertEquals("two", read.commitMessage());
    }

    /**
     * The bug: the next number was {@code MAX(version) + 1}, so deleting the top version handed its number to a
     * different body, and a reader pinned to that number silently got new content.
     */
    @Test
    void deletedTopVersionNumberIsNeverReused() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-ctl-no-reuse");
        TenantContext me = owner(fix);
        String org = fix.org().slug();
        String proj = fix.project().slug();
        String id = requireNonNull(
                        controller.create(me, org, proj, text("p", null)).data())
                .id();
        controller.createVersion(me, org, proj, id, new CreateVersionRequest("two", null, null, null));
        controller.deleteVersion(me, org, proj, id, 2);

        PromptVersionView next = requireNonNull(controller
                .createVersion(me, org, proj, id, new CreateVersionRequest("three", null, null, null))
                .data());

        assertEquals(3, next.version());
        assertError(PromptsError.VERSION_NOT_FOUND, () -> controller.getVersion(me, org, proj, id, 2));
        assertEquals(List.of("latest"), next.labels());
    }

    /** The list filters by folder, label and type, rejects an unknown type, and pages from page 1. */
    @Test
    void listFiltersAndPages() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-ctl-list");
        TenantContext me = owner(fix);
        String org = fix.org().slug();
        String proj = fix.project().slug();
        controller.create(me, org, proj, text("team/a", List.of("production")));
        controller.create(me, org, proj, text("team/b", null));
        controller.create(me, org, proj, text("other", null));

        PromptPage folder = requireNonNull(controller
                .list(me, org, proj, null, null, "team", null, null, "text", 0, 1)
                .data());
        assertEquals(2, folder.total());
        assertEquals(1, folder.items().size());
        assertEquals(1, folder.page());
        PromptPage labeled = requireNonNull(controller
                .list(me, org, proj, " ", null, null, "production", null, null, null, null)
                .data());
        assertEquals(
                List.of(new LabelRef("production", 1)),
                labeled.items().getFirst().labels());
        assertEquals(50, labeled.limit());
        assertError(
                CommonError.INVALID_PARAMETER,
                () -> controller.list(me, org, proj, null, null, null, null, null, "json", null, null));
    }

    @Test
    void tagsFolderAndDeleteRoutes() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-ctl-delete");
        TenantContext me = owner(fix);
        String org = fix.org().slug();
        String proj = fix.project().slug();
        String id = requireNonNull(
                        controller.create(me, org, proj, text("solo", null)).data())
                .id();
        controller.create(me, org, proj, text("team/a", null));
        controller.create(me, org, proj, text("team/b", null));

        assertEquals(
                List.of("x", "y"),
                requireNonNull(controller
                                .updateTags(me, org, proj, id, new UpdateTagsRequest(List.of("x", "y", "x")))
                                .data())
                        .tags());
        assertError(PromptsError.LAST_VERSION, () -> controller.deleteVersion(me, org, proj, id, 1));
        controller.createVersion(me, org, proj, id, new CreateVersionRequest("v2", null, List.of("production"), null));
        assertError(PromptsError.VERSION_LABELED, () -> controller.deleteVersion(me, org, proj, id, 2));
        assertEquals(
                true,
                requireNonNull(controller.deleteVersion(me, org, proj, id, 1).data())
                        .deleted());
        assertEquals(
                2,
                requireNonNull(controller.deleteFolder(me, org, proj, "team").data())
                        .deletedCount());
        assertEquals(
                true,
                requireNonNull(controller.delete(me, org, proj, id).data()).deleted());
        assertEquals(
                false,
                requireNonNull(controller.delete(me, org, proj, id).data()).deleted());
        assertError(PromptsError.NOT_FOUND, () -> controller.get(me, org, proj, id));
    }

    private static void assertError(ErrorCode expected, Executable call) {
        assertEquals(expected, assertThrows(TessaryException.class, call).error());
    }
}
