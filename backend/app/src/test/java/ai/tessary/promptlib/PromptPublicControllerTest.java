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
import ai.tessary.promptlib.PromptPublicDtos.PromptList;
import ai.tessary.promptlib.PromptPublicDtos.PublicCreatePromptRequest;
import ai.tessary.promptlib.PromptPublicDtos.PublicPrompt;
import ai.tessary.promptlib.PromptPublicDtos.UpdateLabelsRequest;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.web.ApiResponse;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code /v1/prompts} through the controller, service and Postgres. The bugs: an ingest key reading or a query key
 * writing, a POST of an existing name failing instead of versioning, a fetch that reads the wrong version for no
 * label or {@code latest}, {@code isActive} not tracking {@code production}, and a delete that picks the wrong target.
 */
@SpringBootTest
record PromptPublicControllerTest(PromptPublicController controller, TenantService tenants) {

    private static TenantContext key(TenantFixture.Setup fix, KeyScope scope) {
        return new TenantContext(
                fix.user().id(), null, fix.org().id(), fix.project().id(), "member", "key-1", scope);
    }

    private static PublicCreatePromptRequest post(
            String name, @Nullable PromptType type, Object prompt, @Nullable List<String> labels) {
        return new PublicCreatePromptRequest(name, type, prompt, null, labels, null, null);
    }

    private static <T> T data(ApiResponse<T> response) {
        return requireNonNull(response.data());
    }

    @Test
    void scopes() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-scopes");
        TenantContext write = key(fix, KeyScope.WRITE);
        TenantContext query = key(fix, KeyScope.QUERY);
        TenantContext admin = key(fix, KeyScope.ADMIN);

        assertError(PromptsError.WRONG_KEY_SCOPE, () -> controller.fetch(write, "p", null, null));
        assertError(PromptsError.WRONG_KEY_SCOPE, () -> controller.create(write, post("p", null, "x", null)));
        assertError(PromptsError.WRONG_KEY_SCOPE, () -> controller.create(query, post("p", null, "x", null)));

        ApiResponse<PublicPrompt> created = controller.create(admin, post("p", null, "x", List.of("production")));
        assertEquals(200, created.meta().status());
        assertEquals(1, data(created).version());
        assertEquals(PromptType.TEXT, data(created).type());
        assertEquals(1, data(controller.fetch(query, "p", null, null)).version());
    }

    @Test
    void postSameNameCreatesNextVersion_typeMismatch() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-post");
        TenantContext admin = key(fix, KeyScope.ADMIN);

        assertEquals(
                1,
                data(controller.create(admin, post("p", PromptType.TEXT, "one", null)))
                        .version());
        PublicPrompt v2 = data(controller.create(admin, post("p", null, "two", List.of("staging"))));
        assertEquals(2, v2.version());
        assertEquals(List.of("staging", "latest"), v2.labels());
        assertError(
                PromptsError.TYPE_MISMATCH,
                () -> controller.create(
                        admin, post("p", PromptType.CHAT, List.of(Map.of("role", "user", "content", "x")), null)));
    }

    @Test
    void fetchDefaultsAndLatest() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-fetch");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", List.of("production")));
        controller.create(admin, post("p", null, "two", null));
        controller.create(admin, post("bare", null, "x", null));

        PublicPrompt bare = data(controller.fetch(admin, "p", null, null));
        assertEquals(1, bare.version());
        assertEquals(true, bare.isActive());
        assertEquals("one", bare.prompt());
        PublicPrompt latest = data(controller.fetch(admin, "p", null, "latest"));
        assertEquals(2, latest.version());
        assertEquals(false, latest.isActive());
        assertEquals(List.of("latest"), latest.labels());
        assertError(PromptsError.VERSION_NOT_FOUND, () -> controller.fetch(admin, "p", 9, null));
        assertError(PromptsError.LABEL_NOT_FOUND, () -> controller.fetch(admin, "bare", null, null));
        assertError(PromptsError.NAME_REQUIRED, () -> controller.fetch(admin, " ", null, null));
        assertError(PromptsError.BOTH_VERSION_AND_LABEL, () -> controller.fetch(admin, "p", 1, "production"));
    }

    @Test
    void patchLabelsAddsAndRejectsLatest() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-patch");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", List.of("production")));
        controller.create(admin, post("p", null, "two", null));

        PublicPrompt moved = data(controller.updateLabels(admin, new UpdateLabelsRequest("p", 1, List.of("staging"))));
        assertEquals(List.of("production", "staging"), moved.labels());
        assertError(
                PromptsError.LATEST_RESERVED,
                () -> controller.updateLabels(admin, new UpdateLabelsRequest("p", 2, List.of("latest"))));
        assertError(
                PromptsError.NOT_FOUND,
                () -> controller.updateLabels(admin, new UpdateLabelsRequest("nope", 1, List.of("staging"))));
    }

    @Test
    void deleteBranches() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-delete");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", List.of("production", "staging")));
        controller.create(admin, post("p", null, "two", null));

        assertError(PromptsError.BOTH_VERSION_AND_LABEL, () -> controller.delete(admin, "p", 1, "staging"));
        assertEquals(true, data(controller.delete(admin, "p", null, "staging")).deleted());
        assertEquals(false, data(controller.delete(admin, "p", null, "staging")).deleted());
        assertError(PromptsError.LATEST_RESERVED, () -> controller.delete(admin, "p", null, "latest"));
        assertError(PromptsError.VERSION_LABELED, () -> controller.delete(admin, "p", 1, null));
        assertEquals(true, data(controller.delete(admin, "p", 2, null)).deleted());
        assertEquals(true, data(controller.delete(admin, "p", null, null)).deleted());
        assertError(PromptsError.NOT_FOUND, () -> controller.fetch(admin, "p", null, null));
        assertError(PromptsError.NAME_REQUIRED, () -> controller.delete(admin, null, null, null));
    }

    @Test
    void listFiltersByLabelTagAndWindow() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-list");
        TenantContext query = key(fix, KeyScope.QUERY);
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(
                admin, new PublicCreatePromptRequest("b", null, "x", null, List.of("production"), List.of("t1"), null));
        controller.create(admin, post("a", null, "x", null));

        PromptList labeled =
                data(controller.list(query, null, "production", "t1", null, null, "2000-01-01T00:00:00Z", null));
        assertEquals(List.of("b"), labeled.data().stream().map(m -> m.name()).toList());
        PromptList none = data(controller.list(query, "zzz", null, null, null, null, null, " "));
        assertEquals(0, none.meta().totalPages());
        assertError(
                CommonError.INVALID_PARAMETER,
                () -> controller.list(query, null, null, null, null, null, "yesterday", null));
    }

    private static void assertError(ErrorCode expected, Executable call) {
        assertEquals(expected, assertThrows(TessaryException.class, call).error());
    }
}
