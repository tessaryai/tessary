// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.auth.TenantContext;
import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.CommonError;
import ai.tessary.open.errors.ErrorCode;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.promptlib.PromptPublicDtos.PromptList;
import ai.tessary.promptlib.PromptPublicDtos.PublicCreatePromptRequest;
import ai.tessary.promptlib.PromptPublicDtos.PublicPrompt;
import ai.tessary.promptlib.PromptPublicDtos.UpdateLabelsRequest;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.TenantService;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.TenantFixture;
import ai.tessary.web.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code /v1/prompts} through the controller, service and Postgres. The bugs: an ingest key reading or a query key
 * writing, a POST of an existing name failing instead of versioning, a fetch that reads the wrong version for no
 * label or {@code latest}, and a delete that picks the wrong target.
 */
@SpringBootTest
record PromptPublicControllerTest(
        PromptPublicController controller,
        TenantService tenants,
        PromptService service,
        CapabilityFixture capabilities,
        PlatformTransactionManager transactionManager) {

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
    void aWriteAnswersWithTheUpdatedAtItWrote() throws InterruptedException {
        // Bug: a next version or a label move answers with the prompt's previous updated_at, so a client that tracks
        // changes by it sees the write as older than it is.
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-updated-at");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        String first =
                data(controller.create(admin, post("p", null, "one", null))).updatedAt();
        Thread.sleep(5);

        PublicPrompt next = data(controller.create(admin, post("p", null, "two", null)));
        assertEquals(data(controller.fetch(admin, "p", 2, null)).updatedAt(), next.updatedAt());
        assertTrue(next.updatedAt().compareTo(first) > 0, next.updatedAt() + " after " + first);
        Thread.sleep(5);

        PublicPrompt moved = data(controller.updateLabels(
                admin, new PromptPublicDtos.UpdateLabelsRequest("p", 2, List.of("production"), null)));
        assertEquals(data(controller.fetch(admin, "p", 2, null)).updatedAt(), moved.updatedAt());
        assertTrue(moved.updatedAt().compareTo(next.updatedAt()) > 0);
    }

    @Test
    void scopes() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-scopes");
        TenantContext write = key(fix, KeyScope.WRITE);
        TenantContext query = key(fix, KeyScope.QUERY);
        TenantContext admin = key(fix, KeyScope.ADMIN);

        // Bug: the ingest key an application sends traces with cannot fetch its prompts, or either read key can write.
        assertError(PromptsError.WRONG_KEY_SCOPE, () -> controller.create(write, post("p", null, "x", null)));
        assertError(PromptsError.WRONG_KEY_SCOPE, () -> controller.create(query, post("p", null, "x", null)));

        ApiResponse<PublicPrompt> created = controller.create(admin, post("p", null, "x", List.of("production")));
        assertEquals(201, created.meta().status());
        assertEquals(1, data(created).version());
        assertEquals(PromptType.TEXT, data(created).type());
        assertEquals(1, data(controller.fetch(query, "p", null, null)).version());
        assertEquals(1, data(controller.fetch(write, "p", null, null)).version());
        assertEquals(
                1,
                data(controller.list(write, "p", null, null, null, null, null, null))
                        .items()
                        .size());
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
        assertEquals(List.of("production"), bare.labels());
        assertEquals("one", bare.body());
        PublicPrompt latest = data(controller.fetch(admin, "p", null, "latest"));
        assertEquals(2, latest.version());
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

        PublicPrompt moved =
                data(controller.updateLabels(admin, new UpdateLabelsRequest("p", 1, List.of("staging"), null)));
        assertEquals(List.of("production", "staging"), moved.labels());
        assertError(
                PromptsError.LATEST_RESERVED,
                () -> controller.updateLabels(admin, new UpdateLabelsRequest("p", 2, List.of("latest"), null)));
        assertError(
                PromptsError.NOT_FOUND,
                () -> controller.updateLabels(admin, new UpdateLabelsRequest("nope", 1, List.of("staging"), null)));
    }

    @Test
    void deleteBranches() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-delete");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", List.of("production", "staging")));
        controller.create(admin, post("p", null, "two", null));
        controller.create(admin, post("p", null, "three", null));

        assertError(PromptsError.BOTH_VERSION_AND_LABEL, () -> controller.delete(admin, "p", 1, "staging"));
        assertError(PromptsError.LATEST_RESERVED, () -> controller.delete(admin, "p", null, "latest"));
        assertError(PromptsError.VERSION_NOT_FOUND, () -> controller.delete(admin, "p", 9, null));
        assertEquals(true, data(controller.delete(admin, "p", 3, null)).deleted());
        assertEquals(2, data(controller.fetch(admin, "p", null, "latest")).version());
        assertEquals(true, data(controller.delete(admin, "p", null, null)).deleted());
        assertError(PromptsError.NOT_FOUND, () -> controller.fetch(admin, "p", null, null));
        assertError(PromptsError.NAME_REQUIRED, () -> controller.delete(admin, null, null, null));
    }

    /**
     * A delete by label deletes the version that carries the label, labeled or not. The bug: a
     * migration script that deletes by label leaves the version in place, moves only the label, and reports success.
     */
    @Test
    void deleteByLabelDeletesTheVersionThatCarriesIt() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-delete-label");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", List.of("production")));
        controller.create(admin, post("p", null, "two", List.of("staging", "canary")));
        controller.create(admin, post("p", null, "three", null));

        assertEquals(true, data(controller.delete(admin, "p", null, "staging")).deleted());

        assertError(PromptsError.VERSION_NOT_FOUND, () -> controller.fetch(admin, "p", 2, null));
        assertError(PromptsError.LABEL_NOT_FOUND, () -> controller.fetch(admin, "p", null, "canary"));
        assertEquals(1, data(controller.fetch(admin, "p", null, null)).version());
        assertEquals(true, data(controller.delete(admin, "p", 1, null)).deleted());
        assertError(PromptsError.LABEL_NOT_FOUND, () -> controller.fetch(admin, "p", null, null));
    }

    /** The bug: a typo in the label answers 200 and deletes nothing, so the script thinks it worked. */
    @Test
    void deleteByUnknownLabelIs404() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-delete-unknown");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", List.of("production")));

        assertError(PromptsError.LABEL_NOT_FOUND, () -> controller.delete(admin, "p", null, "prodution"));
        assertEquals(1, data(controller.fetch(admin, "p", null, null)).version());
    }

    /** The bug: deleting the last version leaves an empty prompt that every fetch answers 404 for by version. */
    @Test
    void deletingTheLastVersionDeletesThePrompt() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-delete-last");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", List.of("production")));

        assertEquals(
                true, data(controller.delete(admin, "p", null, "production")).deleted());

        assertError(PromptsError.NOT_FOUND, () -> controller.fetch(admin, "p", null, "latest"));
        assertEquals(
                1,
                data(controller.create(admin, post("p", null, "again", null))).version());
    }

    /**
     * Tags sent with a POST to an existing name replace the prompt's tags; no {@code tags} keeps them. The bug: the
     * tags of a new version are dropped with no error, so a script that tags on publish never tags anything.
     */
    @Test
    void postToExistingNameWithTagsReplacesTags_withoutTagsKeepsThem() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-tags");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, new PublicCreatePromptRequest("p", null, "one", null, null, List.of("a"), null));

        PublicPrompt replaced = data(controller.create(
                admin, new PublicCreatePromptRequest("p", null, "two", null, null, List.of("b", "c"), null)));
        PublicPrompt kept = data(controller.create(admin, post("p", null, "three", null)));
        PublicPrompt cleared = data(controller.create(
                admin, new PublicCreatePromptRequest("p", null, "four", null, null, List.of(), null)));

        assertEquals(List.of("b", "c"), replaced.tags());
        assertEquals(List.of("b", "c"), kept.tags());
        assertEquals(List.of(), cleared.tags());
        assertEquals(List.of(), data(controller.fetch(admin, "p", 2, null)).tags());
    }

    /**
     * {@code removeLabels} takes labels off the named version only, after {@code newLabels} moved. The bug: removing
     * {@code staging} from version 2 removes it from version 3, which holds it.
     */
    @Test
    void patchRemoveLabelsDropsOnlyFromThatVersion() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-remove-labels");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", List.of("production")));
        controller.create(admin, post("p", null, "two", null));
        controller.create(admin, post("p", null, "three", List.of("staging")));

        PublicPrompt v2 =
                data(controller.updateLabels(admin, new UpdateLabelsRequest("p", 2, null, List.of("staging"))));
        assertEquals(List.of(), v2.labels());
        assertEquals(
                List.of("staging", "latest"),
                data(controller.fetch(admin, "p", 3, null)).labels());

        PublicPrompt v1 = data(
                controller.updateLabels(admin, new UpdateLabelsRequest("p", 1, List.of("dev"), List.of("production"))));
        assertEquals(List.of("dev"), v1.labels());
        assertError(PromptsError.LABEL_NOT_FOUND, () -> controller.fetch(admin, "p", null, null));
    }

    /** The bug: a label in both lists ends up on or off the version depending on processing order. */
    @Test
    void patchRefusesALabelInBothLists() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-both-lists");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", List.of("production")));

        assertError(
                CommonError.INVALID_PARAMETER,
                () -> controller.updateLabels(
                        admin, new UpdateLabelsRequest("p", 1, List.of("staging"), List.of("staging"))));
        assertError(
                PromptsError.LATEST_RESERVED,
                () -> controller.updateLabels(admin, new UpdateLabelsRequest("p", 1, null, List.of("latest"))));
        assertEquals(
                List.of("production", "latest"),
                data(controller.fetch(admin, "p", null, null)).labels());
    }

    /**
     * A name may hold spaces and is trimmed, on writes and on lookups alike. The bugs: {@code Support Agent} cannot be
     * created, and a second POST of {@code " Support Agent "} misses the stored name, tries to create it again and
     * fails instead of writing version 2.
     */
    @Test
    void postTrimsTheNameAndASpacedLookupFindsIt() {
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-spaces");
        TenantContext admin = key(fix, KeyScope.ADMIN);

        PublicPrompt v1 = data(controller.create(admin, post(" Support Agent ", null, "one", List.of("production"))));
        PublicPrompt v2 = data(controller.create(admin, post("Support Agent\t", null, "two", null)));

        assertEquals("Support Agent", v1.name());
        assertEquals(2, v2.version());
        assertEquals(
                1, data(controller.fetch(admin, "  Support Agent", null, null)).version());
        assertEquals(
                List.of("Support Agent"),
                data(controller.list(admin, null, null, null, null, null, null, null)).items().stream()
                        .map(m -> m.name())
                        .toList());
        assertError(PromptsError.INVALID_NAME, () -> controller.create(admin, post("<b>x</b>", null, "x", null)));
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
        assertEquals(List.of("b"), labeled.items().stream().map(m -> m.name()).toList());
        PromptList none = data(controller.list(query, "zzz", null, null, null, null, null, " "));
        assertEquals(0, none.total());
        assertError(
                CommonError.INVALID_PARAMETER,
                () -> controller.list(query, null, null, null, null, null, "yesterday", null));
    }

    @Test
    void aLabelMoveShowsOnTheNextFetch() {
        // Bug: after production moves, the server keeps serving the cached old version for the full TTL.
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-cache-move");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", List.of("production")));
        controller.create(admin, post("p", null, "two", null));
        assertEquals(1, data(controller.fetch(admin, "p", null, null)).version());

        controller.updateLabels(admin, new UpdateLabelsRequest("p", 2, List.of("production"), null));

        assertEquals(2, data(controller.fetch(admin, "p", null, null)).version());
    }

    @Test
    void aNextVersionShowsOnTheNextLatestFetch() {
        // Bug: creating a version leaves the cached latest in place, so label=latest answers the old body.
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-cache-next");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", null));
        assertEquals(1, data(controller.fetch(admin, "p", null, "latest")).version());

        controller.create(admin, post("p", null, "two", null));

        assertEquals(2, data(controller.fetch(admin, "p", null, "latest")).version());
    }

    @Test
    void aDeletedVersionIsNotServedFromTheCache() {
        // Bug: a version delete by number or by label leaves the cached version, so a fetch serves a deleted body.
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-cache-delete-version");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", List.of("production")));
        controller.create(admin, post("p", null, "two", List.of("staging")));
        controller.create(admin, post("p", null, "three", null));
        assertEquals(3, data(controller.fetch(admin, "p", 3, null)).version());
        assertEquals(2, data(controller.fetch(admin, "p", null, "staging")).version());

        controller.delete(admin, "p", 3, null);
        controller.delete(admin, "p", null, "staging");

        assertError(PromptsError.VERSION_NOT_FOUND, () -> controller.fetch(admin, "p", 3, null));
        assertError(PromptsError.LABEL_NOT_FOUND, () -> controller.fetch(admin, "p", null, "staging"));
    }

    @Test
    void aDeletedPromptIsNotServedFromTheCache() {
        // Bug: a prompt delete leaves its cached versions, so the SDK keeps receiving a prompt that no longer exists.
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-cache-delete-prompt");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", List.of("production")));
        assertEquals(1, data(controller.fetch(admin, "p", null, null)).version());

        controller.delete(admin, "p", null, null);

        assertError(PromptsError.NOT_FOUND, () -> controller.fetch(admin, "p", null, null));
    }

    @Test
    void invalidationRunsAfterCommit() {
        // Bug: the write drops the cache before it commits, a reader on another connection caches the old row again
        // under the new generation, and the fetch after the commit keeps serving it for the full TTL.
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-cache-commit");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", List.of("production")));
        controller.create(admin, post("p", null, "two", null));
        assertEquals(1, data(controller.fetch(admin, "p", null, null)).version());
        String projectId = fix.project().id();
        PromptRow prompt = service.findByName(projectId, "p").orElseThrow();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.setLabels(
                    projectId, prompt.id(), 2, fix.user().id(), Set.of("production"), PromptService.LabelMode.ADD);
            int seenMidWrite;
            try (ExecutorService other = Executors.newSingleThreadExecutor()) {
                seenMidWrite = other.submit(() ->
                                data(controller.fetch(admin, "p", null, null)).version())
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError("the concurrent fetch failed", e);
            }
            assertEquals(1, seenMidWrite, "the move is not committed yet, so another connection reads version 1");
        });

        assertEquals(2, data(controller.fetch(admin, "p", null, null)).version());
    }

    @Test
    void capabilityOffAnswers403EvenWhenCached() {
        // Bug: the cache serves a body to an org that turned prompt management off.
        var fix = TenantFixture.bootstrap(tenants, "prompt-pub-cache-capability");
        TenantContext admin = key(fix, KeyScope.ADMIN);
        controller.create(admin, post("p", null, "one", List.of("production")));
        controller.fetch(admin, "p", null, null);
        capabilities.withhold(fix.org().id(), Capability.PROMPT_MANAGEMENT);

        assertError(CapabilityError.DISABLED, () -> controller.fetch(admin, "p", null, null));
    }

    private static void assertError(ErrorCode expected, Executable call) {
        assertEquals(expected, assertThrows(TessaryException.class, call).error());
    }
}
