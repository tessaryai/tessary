// SPDX-License-Identifier: Apache-2.0
package ai.tessary.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.metrics.dashboard.DefaultDashboard;
import ai.tessary.metrics.dashboard.MetricDashboardController;
import ai.tessary.metrics.dashboard.MetricDashboardDefinition;
import ai.tessary.metrics.dashboard.MetricDashboardDefinition.MetricWidgetDefinition;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboard;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardCreateRequest;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardList;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardSummary;
import ai.tessary.metrics.dashboard.MetricDashboardDtos.MetricDashboardUpdateRequest;
import ai.tessary.metrics.dashboard.MetricDashboardRepository;
import ai.tessary.metrics.dashboard.MetricDashboardRow;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.MetricsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.TenantService;
import ai.tessary.tenant.rbac.Role;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.testsupport.TenantFixture.Setup;
import ai.tessary.web.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dashboards end to end against a real Postgres: the controller's access rules, the service's rules and the
 * {@code metric_dashboard} table together. Controller methods are called directly, so the checks hold without any UI.
 */
@SpringBootTest
record MetricDashboardIntegrationTest(
        TenantService tenants,
        OrgMembershipRepository memberships,
        MetricDashboardController controller,
        MetricDashboardRepository repo,
        JdbcClient jdbc,
        ObjectMapper mapper) {

    /** The bug: a column dropped or swapped in or out, or the jsonb definition unreadable by the strict mapper. */
    @Test
    void aRowRoundTripsEveryColumn() throws Exception {
        Setup s = TenantFixture.bootstrap(tenants, "mdash-roundtrip");
        Instant before = Instant.now();

        MetricDashboard made = create(s, "Ops", "Spend watch", DefaultDashboard.DEFINITION, null);

        MetricDashboardRow row = repo.find(s.project().id(), made.id()).orElseThrow();
        assertEquals(made.id(), row.id());
        assertEquals(s.project().id(), row.projectId());
        assertEquals("Ops", row.name());
        assertEquals("Spend watch", row.description());
        assertEquals(s.user().id(), row.createdBy());
        assertEquals(made.createdAt(), row.createdAt());
        assertEquals(row.createdAt(), row.updatedAt());
        assertFalse(Instant.parse(row.createdAt()).isBefore(before));
        assertEquals(
                DefaultDashboard.DEFINITION, mapper.readValue(row.definitionJson(), MetricDashboardDefinition.class));

        assertEquals(made, data(controller.get(owner(s), slug(s), proj(s), made.id())));
        assertFalse(made.builtIn());
        assertTrue(made.canManage());
    }

    /** The bug: the built-in missing from the switcher, or copies listed in insertion order instead of by name. */
    @Test
    void listPutsDefaultFirstThenRowsByName() {
        Setup s = TenantFixture.bootstrap(tenants, "mdash-list");
        create(s, "Zeta", null, oneTile(), null);
        create(s, "Alpha", null, MetricDashboardDefinition.empty(), null);
        create(s, "Mid", null, oneTile(), null);

        MetricDashboardList list = data(controller.list(owner(s), slug(s), proj(s)));

        assertEquals(
                List.of("default", "Alpha", "Mid", "Zeta"),
                list.dashboards().stream()
                        .map(d -> d.builtIn() ? d.id() : d.name())
                        .toList());
        assertEquals(DefaultDashboard.summary(), list.dashboards().get(0));
        assertEquals(
                List.of(9, 0, 1, 1),
                list.dashboards().stream()
                        .map(MetricDashboardSummary::widgetCount)
                        .toList());
        assertTrue(list.canManage());
    }

    /** The bug: the built-in edited or deleted through the API, though it has no row to change. */
    @Test
    void putAndDeleteOnDefaultAre409() {
        Setup s = TenantFixture.bootstrap(tenants, "mdash-builtin");
        assertCode(
                MetricsError.BUILT_IN_READ_ONLY,
                HttpStatus.CONFLICT,
                () -> controller.update(
                        owner(s),
                        slug(s),
                        proj(s),
                        "default",
                        new MetricDashboardUpdateRequest("Default", null, oneTile())));
        assertCode(
                MetricsError.BUILT_IN_READ_ONLY,
                HttpStatus.CONFLICT,
                () -> controller.delete(owner(s), slug(s), proj(s), "default"));
        assertEquals(0, repo.count(s.project().id()));
    }

    /** The bug: the unique index on (project, name) missing, or its violation surfacing as a 500. */
    @Test
    void aDuplicateNameIs409() {
        Setup s = TenantFixture.bootstrap(tenants, "mdash-dupe");
        create(s, "Ops", null, oneTile(), null);
        MetricDashboard other = create(s, "Other", null, oneTile(), null);

        assertCode(MetricsError.DUPLICATE_NAME, HttpStatus.CONFLICT, () -> create(s, "Ops", null, oneTile(), null));
        assertCode(
                MetricsError.DUPLICATE_NAME,
                HttpStatus.CONFLICT,
                () -> controller.update(
                        owner(s),
                        slug(s),
                        proj(s),
                        other.id(),
                        new MetricDashboardUpdateRequest("Ops", null, oneTile())));

        Setup elsewhere = TenantFixture.bootstrap(tenants, "mdash-dupe-other");
        assertEquals("Ops", create(elsewhere, "Ops", null, oneTile(), null).name());
    }

    /** The bug: a missing id answered 200 or 500, or another project's dashboard reached through this path. */
    @Test
    void anUnknownIdIs404OnGetPutAndDelete() {
        Setup s = TenantFixture.bootstrap(tenants, "mdash-404");
        Setup other = TenantFixture.bootstrap(tenants, "mdash-404-other");
        String foreign = create(other, "Theirs", null, oneTile(), null).id();

        for (String id : List.of("nope", foreign)) {
            assertCode(
                    MetricsError.DASHBOARD_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    () -> controller.get(owner(s), slug(s), proj(s), id));
            assertCode(
                    MetricsError.DASHBOARD_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    () -> controller.update(
                            owner(s), slug(s), proj(s), id, new MetricDashboardUpdateRequest("X", null, oneTile())));
            assertCode(
                    MetricsError.DASHBOARD_NOT_FOUND,
                    HttpStatus.NOT_FOUND,
                    () -> controller.delete(owner(s), slug(s), proj(s), id));
        }
        assertEquals(1, repo.count(other.project().id()));
    }

    /** The bug: the widget or dashboard cap enforced in the UI only, so a direct API call stores past it. */
    @Test
    void theThirteenthWidgetAndTheTwentyFirstDashboardAre400() {
        Setup s = TenantFixture.bootstrap(tenants, "mdash-caps");
        List<MetricWidgetDefinition> thirteen = new ArrayList<>();
        for (int i = 0; i < 13; i++) thirteen.add(tile("w" + i, (i % 4) * 3, (i / 4) * 2));
        assertCode(
                MetricsError.TOO_MANY_WIDGETS,
                HttpStatus.BAD_REQUEST,
                () -> create(s, "Busy", null, new MetricDashboardDefinition(1, "7d", thirteen), null));

        for (int i = 1; i <= 20; i++) create(s, "D" + i, null, oneTile(), null);
        assertCode(
                MetricsError.TOO_MANY_DASHBOARDS,
                HttpStatus.BAD_REQUEST,
                () -> create(s, "D21", null, oneTile(), null));
        assertEquals(20, repo.count(s.project().id()));
    }

    /** The bug: "Make a copy" of the built-in storing an empty dashboard, or a copy of a missing source made anyway. */
    @Test
    void cloneOfDefaultCopiesNineWidgetsAndCloneOfAnUnknownIdIs404() {
        Setup s = TenantFixture.bootstrap(tenants, "mdash-clone");
        MetricDashboard copy = create(s, "Default (copy)", null, null, "default");
        assertEquals(DefaultDashboard.DEFINITION, copy.definition());
        assertEquals(9, copy.definition().widgets().size());
        assertEquals("", copy.description());

        MetricDashboard second = create(s, "Copy of copy", null, null, copy.id());
        assertEquals(DefaultDashboard.DEFINITION, second.definition());

        assertCode(
                MetricsError.DASHBOARD_NOT_FOUND,
                HttpStatus.NOT_FOUND,
                () -> create(s, "Ghost copy", null, null, "ghost"));
        assertEquals(2, repo.count(s.project().id()));
    }

    /** The bug: the foreign key without ON DELETE CASCADE, so deleting a project fails or leaves orphan dashboards. */
    @Test
    void deletingTheProjectCascadesTheDashboards() {
        Setup s = TenantFixture.bootstrap(tenants, "mdash-cascade");
        create(s, "One", null, oneTile(), null);
        create(s, "Two", null, oneTile(), null);
        assertEquals(2, repo.count(s.project().id()));

        jdbc.sql("DELETE FROM project WHERE id = :pid")
                .param("pid", s.project().id())
                .update();

        assertEquals(0, repo.count(s.project().id()));
    }

    /** The bug: a viewer offered the editor (can_manage true) or able to write a dashboard by calling the API. */
    @Test
    void aViewerSeesCanManageFalseAndGets403OnPost() {
        Setup s = TenantFixture.bootstrap(tenants, "mdash-viewer");
        TenantContext viewer = session(withRole(s, Role.VIEWER.wire(), "viewer"));

        assertFalse(data(controller.list(viewer, slug(s), proj(s))).canManage());
        assertFalse(data(controller.get(viewer, slug(s), proj(s), "default")).canManage());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.create(
                        viewer, slug(s), proj(s), new MetricDashboardCreateRequest("Mine", null, oneTile(), null)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals(0, repo.count(s.project().id()));
    }

    /** The bug: dashboard edits gated on admin, so a plain member cannot keep their own dashboards. */
    @Test
    void aMemberCanCreateUpdateAndDelete() {
        Setup s = TenantFixture.bootstrap(tenants, "mdash-member");
        Principal member = withRole(s, OrgMembership.MEMBER, "member");
        TenantContext ctx = session(member);

        MetricDashboard made = data(controller.create(
                ctx, slug(s), proj(s), new MetricDashboardCreateRequest("Mine", "first", oneTile(), null)));
        assertEquals(member.id(), made.createdBy());
        assertTrue(made.canManage());

        MetricDashboard updated = data(controller.update(
                ctx,
                slug(s),
                proj(s),
                made.id(),
                new MetricDashboardUpdateRequest("Mine, renamed", null, MetricDashboardDefinition.empty())));
        assertEquals("Mine, renamed", updated.name());
        assertEquals("", updated.description());
        assertEquals(MetricDashboardDefinition.empty(), updated.definition());
        assertEquals(made.createdAt(), updated.createdAt());
        MetricDashboardRow row = repo.find(s.project().id(), made.id()).orElseThrow();
        assertEquals("Mine, renamed", row.name());
        assertEquals(updated.updatedAt(), row.updatedAt());

        assertNull(controller.delete(ctx, slug(s), proj(s), made.id()).data());
        assertTrue(repo.find(s.project().id(), made.id()).isEmpty());
    }

    /** The bug: an API key edits dashboards because a key context carries role member, which holds ORG_MANAGE. */
    @Test
    void aQueryKeyReadsWithCanManageFalseAndGetsSessionRequiredOnPost() {
        Setup s = TenantFixture.bootstrap(tenants, "mdash-qkey");
        MetricDashboard mine = create(s, "Ops", null, oneTile(), null);

        for (KeyScope scope : List.of(KeyScope.QUERY, KeyScope.ADMIN)) {
            TenantContext key = key(s, scope);
            assertFalse(data(controller.list(key, slug(s), proj(s))).canManage());
            assertFalse(data(controller.get(key, slug(s), proj(s), mine.id())).canManage());

            assertCode(
                    MetricsError.SESSION_REQUIRED,
                    HttpStatus.FORBIDDEN,
                    () -> controller.create(
                            key, slug(s), proj(s), new MetricDashboardCreateRequest("K", null, oneTile(), null)));
            assertCode(
                    MetricsError.SESSION_REQUIRED,
                    HttpStatus.FORBIDDEN,
                    () -> controller.update(
                            key, slug(s), proj(s), mine.id(), new MetricDashboardUpdateRequest("K", null, oneTile())));
            assertCode(
                    MetricsError.SESSION_REQUIRED,
                    HttpStatus.FORBIDDEN,
                    () -> controller.delete(key, slug(s), proj(s), mine.id()));
        }
        assertEquals(1, repo.count(s.project().id()));
    }

    /** The bug: a write-only ingest key reads the project's dashboards. */
    @Test
    void aWriteKeyGetsWrongKeyScopeOnARead() {
        Setup s = TenantFixture.bootstrap(tenants, "mdash-wkey");
        TenantContext key = key(s, KeyScope.WRITE);
        assertCode(MetricsError.WRONG_KEY_SCOPE, HttpStatus.FORBIDDEN, () -> controller.list(key, slug(s), proj(s)));
        assertCode(
                MetricsError.WRONG_KEY_SCOPE,
                HttpStatus.FORBIDDEN,
                () -> controller.get(key, slug(s), proj(s), "default"));
    }

    private MetricDashboard create(
            Setup s,
            String name,
            @Nullable String description,
            @Nullable MetricDashboardDefinition definition,
            @Nullable String cloneOf) {
        return data(controller.create(
                owner(s), slug(s), proj(s), new MetricDashboardCreateRequest(name, description, definition, cloneOf)));
    }

    private Principal withRole(Setup s, String role, String tag) {
        Principal u = tenants.upsertUserFromWorkos(
                "user_mdash_" + tag + "_" + System.nanoTime(),
                "mdash+" + tag + "+" + System.nanoTime() + "@example.com",
                tag,
                null);
        memberships.insert(
                OrgMembership.of(s.org().id(), u.id(), role, Instant.now().toString()));
        return u;
    }

    private static TenantContext owner(Setup s) {
        return session(s.user());
    }

    private static TenantContext session(Principal u) {
        return new TenantContext(u.id(), u.email(), null, null, null, null);
    }

    private static TenantContext key(Setup s, KeyScope scope) {
        return new TenantContext("user-1", null, s.org().id(), s.project().id(), "member", "tok-1", scope);
    }

    private static String slug(Setup s) {
        return s.org().slug();
    }

    private static String proj(Setup s) {
        return s.project().slug();
    }

    private static MetricDashboardDefinition oneTile() {
        return new MetricDashboardDefinition(1, "7d", List.of(tile("spend", 0, 0)));
    }

    private static MetricWidgetDefinition tile(String id, int x, int y) {
        return new MetricWidgetDefinition(
                id, "Spend", "number", "llm_calls", List.of("cost_usd"), null, List.of(), null, null, x, y, 3, 2);
    }

    private static <T> T data(ApiResponse<T> response) {
        return Objects.requireNonNull(response.data());
    }

    private static void assertCode(ErrorCode code, HttpStatus status, Executable call) {
        TessaryException ex = assertThrows(TessaryException.class, call);
        assertSame(code, ex.error());
        assertEquals(status, ex.error().status());
    }
}
