// SPDX-License-Identifier: Apache-2.0
package ai.tessary.promptlib;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ai.tessary.auth.TenantContext;
import ai.tessary.crypto.SecretBox;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ProviderCredential;
import ai.tessary.llm.ProviderCredentialRepository;
import ai.tessary.llm.chat.ChatClient;
import ai.tessary.llm.chat.ChatResult;
import ai.tessary.llm.chat.ChatUsage;
import ai.tessary.open.errors.CapabilityError;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.PromptsError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.plan.Capability;
import ai.tessary.promptlib.PlaygroundDtos.Message;
import ai.tessary.promptlib.PlaygroundDtos.PlaygroundRunRequest;
import ai.tessary.promptlib.PlaygroundDtos.PlaygroundRunView;
import ai.tessary.promptlib.PlaygroundDtos.PromptRef;
import ai.tessary.promptlib.PromptDtos.CreatePromptRequest;
import ai.tessary.tenant.Ids;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Principal;
import ai.tessary.tenant.TenantService;
import ai.tessary.tenant.rbac.Role;
import ai.tessary.testsupport.CapabilityFixture;
import ai.tessary.testsupport.TenantFixture;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * The playground route through the controller, the real resolver and the ledger, with only the provider call faked.
 * The bugs: a viewer or an ingest or query key spending the org's provider key, the run going through with
 * {@code BYO_PROVIDER_KEYS} withheld, a missing key answered as a 401 that signs the SPA out, and a run that books
 * nothing or books it as platform spend.
 */
@SpringBootTest
@MockitoBean(types = ChatClient.class)
record PlaygroundControllerTest(
        PlaygroundController controller,
        PromptController prompts,
        ChatClient chat,
        TenantService tenants,
        OrgMembershipRepository memberships,
        ProviderCredentialRepository credentials,
        SecretBox secretBox,
        CapabilityFixture capabilities,
        JdbcClient jdbc) {

    private static final String MODEL = "gpt-5.5";

    /** A literal public address passes the SSRF guard without a DNS lookup; the faked client never dials it. */
    private static final String PUBLIC_BASE = "https://203.0.113.10/v1";

    private static TenantContext owner(TenantFixture.Setup fix) {
        return new TenantContext(fix.user().id(), fix.user().email(), null, null, null, null);
    }

    private static TenantContext key(TenantFixture.Setup fix, KeyScope scope) {
        return new TenantContext(
                fix.user().id(), null, fix.org().id(), fix.project().id(), "member", "key-1", scope);
    }

    private static PlaygroundRunRequest request(@Nullable PromptRef ref) {
        return new PlaygroundRunRequest(
                ModelProvider.OPENAI, MODEL, List.of(new Message("user", "Say hi.")), Map.of("temperature", 0.2), ref);
    }

    private TenantContext memberWithRole(TenantFixture.Setup fix, Role role) {
        long n = System.nanoTime();
        Principal p = tenants.upsertUserFromWorkos(
                "user_playground_" + role.wire() + "_" + n,
                "playground-" + role.wire() + "+" + n + "@example.com",
                "m",
                null);
        memberships.insert(OrgMembership.of(
                fix.org().id(), p.id(), role.wire(), Instant.now().toString()));
        return new TenantContext(p.id(), p.email(), null, null, null, null);
    }

    private TenantFixture.Setup orgWithOpenAiKey(String slug) {
        var fix = TenantFixture.bootstrap(tenants, slug);
        String now = Instant.now().toString();
        credentials.insert(new ProviderCredential(
                Ids.ulid(),
                fix.org().id(),
                null,
                ModelProvider.OPENAI,
                PUBLIC_BASE,
                secretBox.seal("test-key"),
                null,
                null,
                null,
                null,
                null,
                ProviderCredential.AUTH_MODE_API_KEY,
                now,
                now));
        when(chat.complete(any(), any()))
                .thenReturn(new ChatResult("Hi.", "stop", MODEL, new ChatUsage(20, 8, null, null, null), 1840));
        return fix;
    }

    private static TessaryException tessary(Runnable call) {
        return assertThrows(TessaryException.class, call::run);
    }

    @Test
    void aViewerCannotRunThePlayground() {
        var fix = TenantFixture.bootstrap(tenants, "playground-viewer");

        ResponseStatusException refused = assertThrows(
                ResponseStatusException.class,
                () -> controller.run(
                        memberWithRole(fix, Role.VIEWER),
                        fix.org().slug(),
                        fix.project().slug(),
                        request(null)));

        assertEquals(HttpStatus.FORBIDDEN, refused.getStatusCode());
    }

    @Test
    void aMemberWithOrgManageCanRun() {
        var fix = orgWithOpenAiKey("playground-member");

        PlaygroundRunView view = requireNonNull(controller
                .run(
                        memberWithRole(fix, Role.MEMBER),
                        fix.org().slug(),
                        fix.project().slug(),
                        request(null))
                .data());

        assertEquals(ModelProvider.OPENAI, view.provider());
        assertEquals(MODEL, view.model());
        assertEquals("Hi.", view.content());
    }

    @Test
    void writeAndQueryKeysCannotRun_adminCan() {
        var fix = orgWithOpenAiKey("playground-scope");
        String org = fix.org().slug();
        String proj = fix.project().slug();

        for (KeyScope scope : List.of(KeyScope.WRITE, KeyScope.QUERY)) {
            TessaryException e = tessary(() -> controller.run(key(fix, scope), org, proj, request(null)));
            assertEquals(PromptsError.WRONG_KEY_SCOPE, e.error(), scope.name());
            assertEquals(
                    "this API key cannot run prompts (reads need a query or admin key, writes an admin key)",
                    e.getMessage());
        }
        assertEquals(
                "Hi.",
                requireNonNull(controller
                                .run(key(fix, KeyScope.ADMIN), org, proj, request(null))
                                .data())
                        .content());
    }

    @Test
    void byoProviderKeysOff_is403() {
        var fix = orgWithOpenAiKey("playground-byo-off");
        capabilities.withhold(fix.org().id(), Capability.BYO_PROVIDER_KEYS);

        assertEquals(
                CapabilityError.DISABLED,
                tessary(() -> controller.run(
                                owner(fix), fix.org().slug(), fix.project().slug(), request(null)))
                        .error());
    }

    @Test
    void aMissingProviderKey_is412NotA401() {
        var fix = TenantFixture.bootstrap(tenants, "playground-no-key");

        TessaryException e = tessary(
                () -> controller.run(owner(fix), fix.org().slug(), fix.project().slug(), request(null)));

        assertEquals(ModelConfigError.MISSING_CREDENTIALS, e.error());
        assertEquals(HttpStatus.PRECONDITION_FAILED, e.error().status());
    }

    @Test
    void aRun_writesOneByoLedgerRowUnderLanePlayground() {
        var fix = orgWithOpenAiKey("playground-ledger");
        TenantContext me = owner(fix);
        String org = fix.org().slug();
        String proj = fix.project().slug();
        String promptId = requireNonNull(prompts.create(
                                me,
                                org,
                                proj,
                                new CreatePromptRequest("greet", PromptType.TEXT, "Hi", null, null, null, null))
                        .data())
                .id();
        String versionId = requireNonNull(
                        prompts.getVersion(me, org, proj, promptId, 1).data())
                .id();

        controller.run(me, org, proj, request(new PromptRef(promptId, 1)));

        assertEquals(
                List.of(List.of("playground", "byo", MODEL, "prompt_version", versionId)),
                jdbc.sql("SELECT lane, funding, model, subject_kind, subject_id FROM llm_call WHERE project_id = :pid")
                        .param("pid", fix.project().id())
                        .query((rs, n) -> List.of(
                                rs.getString("lane"),
                                rs.getString("funding"),
                                rs.getString("model"),
                                rs.getString("subject_kind"),
                                rs.getString("subject_id")))
                        .list());
    }
}
