// SPDX-License-Identifier: Apache-2.0
package ai.tessary.agentrun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.tessary.config.AgentRunProperties;
import ai.tessary.config.ModelsDevProperties;
import ai.tessary.config.ObserverProperties;
import ai.tessary.config.RcaProperties;
import ai.tessary.git.GitIntegrationRepository;
import ai.tessary.git.GitIntegrationRow;
import ai.tessary.git.GitProvider;
import ai.tessary.git.GitProviderClient;
import ai.tessary.git.GitProviderFactory;
import ai.tessary.git.GitTokenService;
import ai.tessary.llm.AgenticCredentialResolver;
import ai.tessary.llm.ModelProvider;
import ai.tessary.llm.ModelsDevRates;
import ai.tessary.llm.ProjectModelSettings;
import ai.tessary.llmspi.ModelLane;
import ai.tessary.open.errors.AgentRunError;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.ApiKey;
import ai.tessary.tenant.ApiKeyService;
import ai.tessary.tenant.KeyScope;
import ai.tessary.tenant.OrgMembership;
import ai.tessary.tenant.OrgMembershipRepository;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import ai.tessary.testsupport.Props;
import ai.tessary.usage.LlmUsageAccountant;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link AgentRunService} with a capturing {@link AgentRunSandbox} double and strict mocks around it:
 * the request the sandbox receives, the key and credential lifecycle however the run ends, and the
 * clone's presence following the project's integration.
 */
@ExtendWith(MockitoExtension.class)
class AgentRunServiceTest {

    private static final String PROJECT_ID = "proj1";
    private static final String ORG_ID = "org1";
    private static final String OBSERVER_MODEL = "global.anthropic.claude-sonnet-5-5";

    /** The real lookup over the bundled models.dev copy; a blank URL never fetches. */
    private static final ModelsDevRates RATES = bundledRates();

    private static ModelsDevRates bundledRates() {
        ModelsDevProperties p = Props.of(ModelsDevProperties.class, "url", "");
        return new ModelsDevRates(new ObjectMapper(), p);
    }

    private static final AgenticCredentialResolver.Credential CREDENTIAL =
            new AgenticCredentialResolver.Credential(ModelProvider.GROK, "xai-key", null, null, null, null, null);
    private static final LlmUsageAccountant.Subject SUBJECT = new LlmUsageAccountant.Subject("classifier_draft", "d1");
    private static final Map<String, String> FILES = Map.of("brief.md", "find timeouts");

    @Mock
    ProjectModelSettings modelSettings;

    @Mock
    AgenticCredentialResolver credentials;

    @Mock
    ApiKeyService apiKeys;

    @Mock
    ProjectRepository projects;

    @Mock
    OrgMembershipRepository memberships;

    @Mock
    GitIntegrationRepository integrations;

    /** Records the one request it was handed, or throws what it was told to. */
    private static final class CapturingSandbox implements AgentRunSandbox {
        private AgentRunSandbox.@Nullable Request seen;
        private final @Nullable RuntimeException failure;

        CapturingSandbox(@Nullable RuntimeException failure) {
            this.failure = failure;
        }

        /** The request the sandbox was handed; fails the test when it never was. */
        AgentRunSandbox.Request seen() {
            return Objects.requireNonNull(seen, "the sandbox was never called");
        }

        boolean wasCalled() {
            return seen != null;
        }

        @Override
        public String key() {
            return "e2b";
        }

        @Override
        public Result run(Request req) {
            seen = req;
            if (failure != null) throw failure;
            return new Result(
                    new ObjectMapper().createObjectNode().put("builder", "x"), "{\"builder\":\"x\"}", 3, 1200L);
        }
    }

    /** A null value is a token the provider refuses to mint. */
    private record Header(@Nullable String value) implements GitTokenService {
        @Override
        public GitProvider provider() {
            return GitProvider.GITHUB;
        }

        @Override
        public String authHeader(GitIntegrationRow integ) {
            if (value == null) throw new IllegalStateException("installation token refused");
            return value;
        }
    }

    private record HeadAt(String sha) implements GitProviderClient {
        @Override
        public GitProvider provider() {
            return GitProvider.GITHUB;
        }

        @Override
        public RepoAccess verifyAccess(GitIntegrationRow integ) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public String resolveHeadSha(GitIntegrationRow integ, String branch) {
            return sha;
        }
    }

    private static AgentRunProperties props() {
        return props("https://app.example/", "e2b");
    }

    private static AgentRunProperties props(String mcpBaseUrl, String sandbox) {
        return Props.of(
                AgentRunProperties.class,
                "mcp-base-url",
                mcpBaseUrl,
                "sandbox",
                sandbox,
                "timeout-ms",
                600_000L,
                "max-turns",
                30);
    }

    private static ObserverProperties observer() {
        return Props.of(ObserverProperties.class, "agentic.model", OBSERVER_MODEL);
    }

    private AgentRunService service(AgentRunProperties props, CapturingSandbox sandbox, GitProviderFactory providers) {
        return service(props, Props.of(RcaProperties.class), sandbox, providers);
    }

    private AgentRunService service(
            AgentRunProperties props, RcaProperties rcaProps, CapturingSandbox sandbox, GitProviderFactory providers) {
        return new AgentRunService(
                List.of(sandbox),
                props,
                rcaProps,
                new AgentModels(modelSettings, observer(), RATES),
                credentials,
                apiKeys,
                projects,
                memberships,
                integrations,
                providers);
    }

    private AgentRunService service(CapturingSandbox sandbox) {
        return service(props(), sandbox, new GitProviderFactory(List.of(), List.of()));
    }

    private void anOwnedProject() {
        when(projects.findById(PROJECT_ID))
                .thenReturn(Optional.of(new Project(
                        PROJECT_ID, ORG_ID, "slug", "name", null, "2026-01-01T00:00:00Z", null, null, false, null)));
        when(memberships.findByOrg(ORG_ID))
                .thenReturn(List.of(
                        OrgMembership.of(ORG_ID, "user2", OrgMembership.OWNER, "2026-02-01T00:00:00Z"),
                        OrgMembership.of(ORG_ID, "user1", OrgMembership.OWNER, "2026-01-01T00:00:00Z")));
    }

    private void aMintedKey() {
        ApiKey key = new ApiKey(
                "key1",
                PROJECT_ID,
                "user1",
                "agent_run-d1 (system)",
                "tsy_a_prefix",
                "hash",
                "2026-01-01T00:00:00Z",
                null,
                null,
                "admin",
                null,
                null,
                null);
        when(apiKeys.issue(PROJECT_ID, "user1", "agent_run-d1 (system)", KeyScope.ADMIN))
                .thenReturn(new ApiKeyService.Issued(key, "tsy_a_secret"));
    }

    private void aResolvedGrokLane() {
        when(modelSettings.resolveAgenticModel(PROJECT_ID, ModelLane.AGENT_RUN))
                .thenReturn(Optional.of(
                        new ProjectModelSettings.ResolvedAgenticModel(ModelProvider.GROK, "grok-4.6", "xai/grok-4.6")));
        when(credentials.resolve(PROJECT_ID, ModelProvider.GROK)).thenReturn(CREDENTIAL);
    }

    private AgentRunSandbox.Result run(AgentRunService service) {
        return service.run(PROJECT_ID, ModelLane.AGENT_RUN, "system", "task", FILES, "{\"type\":\"object\"}", SUBJECT);
    }

    /** The whole request, hand-built: a project with no repository sends no clone, and everything else rides. */
    @Test
    void handsTheSandboxTheResolvedRunWithNoCloneWhenThereIsNoRepository() {
        anOwnedProject();
        aMintedKey();
        aResolvedGrokLane();
        when(integrations.findByProject(PROJECT_ID)).thenReturn(Optional.empty());
        CapturingSandbox sandbox = new CapturingSandbox(null);

        AgentRunSandbox.Result result = run(service(sandbox));

        assertEquals(
                new AgentRunSandbox.Request(
                        PROJECT_ID,
                        "agent_run",
                        "system",
                        "task",
                        FILES,
                        "{\"type\":\"object\"}",
                        null,
                        null,
                        "grok-4.6",
                        RATES.cost("xai/grok-4.6").orElseThrow(),
                        "GROK",
                        CREDENTIAL,
                        "https://app.example/mcp",
                        "tsy_a_secret",
                        600_000L,
                        30,
                        SUBJECT),
                sandbox.seen());
        assertEquals("{\"builder\":\"x\"}", result.resultText());
        verify(apiKeys).revoke("key1");
        verify(credentials).release(CREDENTIAL);
    }

    /**
     * With an integration the clone URL carries the minted token and the head sha the provider resolved; a token that
     * cannot be minted runs without the repository rather than failing the run.
     */
    @ParameterizedTest
    @CsvSource(
            nullValues = "NULL",
            value = {"Bearer ghs_tok, https://x-access-token:ghs_tok@github.com/acme/app.git, sha-1", "NULL, NULL, NULL"
            })
    void clonesTheRepositoryAtItsHeadWhenTheProjectHasOne(
            @Nullable String header, @Nullable String cloneUrl, @Nullable String headSha) {
        anOwnedProject();
        aMintedKey();
        aResolvedGrokLane();
        when(integrations.findByProject(PROJECT_ID))
                .thenReturn(Optional.of(new GitIntegrationRow(
                        "gi1", PROJECT_ID, "github", "api.github.com", "acme", "app", "main", "enc", "t0", "t0")));
        CapturingSandbox sandbox = new CapturingSandbox(null);
        GitProviderFactory providers =
                new GitProviderFactory(List.of(new HeadAt("sha-1")), List.of(new Header(header)));

        run(service(props(), sandbox, providers));

        assertEquals(cloneUrl, sandbox.seen().cloneUrl());
        assertEquals(headSha, sandbox.seen().headSha());
    }

    /**
     * The key must not outlive the sandbox and the credential's reservation must be freed, on failure too; a revoke
     * that fails must not mask the run's own outcome.
     */
    @Test
    void revokesTheKeyAndReleasesTheCredentialWhenTheSandboxThrows() {
        anOwnedProject();
        aMintedKey();
        aResolvedGrokLane();
        when(integrations.findByProject(PROJECT_ID)).thenReturn(Optional.empty());
        CapturingSandbox sandbox =
                new CapturingSandbox(new TessaryException(AgentRunError.RUN_FAILED, "kind=script_exit"));
        doThrow(new IllegalStateException("db down")).when(apiKeys).revoke("key1");

        TessaryException ex = assertThrows(TessaryException.class, () -> run(service(sandbox)));

        assertEquals(AgentRunError.RUN_FAILED, ex.error());
        verify(apiKeys).revoke("key1");
        verify(credentials).release(CREDENTIAL);
    }

    /** A lane nobody pinned, on an org with no provider, runs the observer's model on Bedrock as triage does. */
    @Test
    void anUnresolvedLaneRunsTheObserversModelOnBedrock() {
        anOwnedProject();
        aMintedKey();
        when(modelSettings.resolveAgenticModel(PROJECT_ID, ModelLane.AGENT_RUN)).thenReturn(Optional.empty());
        AgenticCredentialResolver.Credential bedrock = new AgenticCredentialResolver.Credential(
                ModelProvider.BEDROCK, null, null, null, "us-east-1", "ak", "sk");
        when(credentials.resolve(PROJECT_ID, ModelProvider.BEDROCK)).thenReturn(bedrock);
        when(integrations.findByProject(PROJECT_ID)).thenReturn(Optional.empty());
        CapturingSandbox sandbox = new CapturingSandbox(null);

        run(service(sandbox));

        assertEquals(OBSERVER_MODEL, sandbox.seen().model());
        assertEquals(
                RATES.cost("amazon-bedrock/" + OBSERVER_MODEL).orElseThrow(),
                sandbox.seen().modelCost(),
                "the deployment default is billed at its Bedrock rates, or the run books unpriced");
        assertEquals("BEDROCK", sandbox.seen().provider());
        assertEquals(bedrock, sandbox.seen().credential());
    }

    /** No credential means no run: nothing is minted for a sandbox that cannot start. */
    @Test
    void aMissingCredentialMintsNoKey() {
        when(modelSettings.resolveAgenticModel(PROJECT_ID, ModelLane.AGENT_RUN)).thenReturn(Optional.empty());
        when(credentials.resolve(PROJECT_ID, ModelProvider.BEDROCK))
                .thenThrow(new TessaryException(ModelConfigError.MISSING_CREDENTIALS, ModelProvider.BEDROCK));
        CapturingSandbox sandbox = new CapturingSandbox(null);

        TessaryException ex = assertThrows(TessaryException.class, () -> run(service(sandbox)));

        assertEquals(ModelConfigError.MISSING_CREDENTIALS, ex.error());
        verifyNoInteractions(apiKeys);
        assertFalse(sandbox.wasCalled());
    }

    /** An org with no owner has nobody to issue the key to; the credential is still released. */
    @Test
    void anOrgWithNoOwnerGetsNoRunAndReleasesTheCredential() {
        aResolvedGrokLane();
        when(projects.findById(PROJECT_ID))
                .thenReturn(Optional.of(new Project(
                        PROJECT_ID, ORG_ID, "slug", "name", null, "2026-01-01T00:00:00Z", null, null, false, null)));
        when(memberships.findByOrg(ORG_ID))
                .thenReturn(List.of(OrgMembership.of(ORG_ID, "user1", OrgMembership.MEMBER, "2026-01-01T00:00:00Z")));
        CapturingSandbox sandbox = new CapturingSandbox(null);

        TessaryException ex = assertThrows(TessaryException.class, () -> run(service(sandbox)));

        assertEquals("The agent run failed: the project's org has no owner to issue a key to", ex.getMessage());
        verify(credentials).release(CREDENTIAL);
        verifyNoInteractions(apiKeys);
    }

    /** Without a door the agent cannot read a trace, so the run is refused before anything is resolved. */
    @Test
    void noMcpOriginAnywhereIsMisconfiguredBeforeAnythingIsResolved() {
        AgentRunProperties bare = props("", "e2b");
        CapturingSandbox sandbox = new CapturingSandbox(null);

        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> run(service(bare, sandbox, new GitProviderFactory(List.of(), List.of()))));

        assertEquals(AgentRunError.LAUNCHER_MISCONFIGURED, ex.error());
        verifyNoInteractions(credentials, apiKeys, modelSettings);
    }

    /**
     * The session client builds its MCP servers from this URL: a trailing slash must not become
     * {@code //mcp}, and a deployment that set only RCA's origin must not point the agent at nothing.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "https://own.example/ | ''                  | https://own.example/mcp",
                "''                   | https://rca.example | https://rca.example/mcp"
            })
    void platformMcpUrlIsTheOwnOriginElseRcasWithOneSlash(String own, String rca, String expected) {
        AgentRunProperties props = props(own, "e2b");
        RcaProperties rcaProps = Props.of(RcaProperties.class, "agentic.mcp-base-url", rca);

        String url = service(props, rcaProps, new CapturingSandbox(null), new GitProviderFactory(List.of(), List.of()))
                .platformMcpUrl();

        assertEquals(expected, url);
    }

    /** A sandbox key nothing registered refuses to boot, rather than failing every run later. */
    @Test
    void anUnregisteredSandboxKeyRefusesToBoot() {
        AgentRunProperties props = props("https://app.example/", "firecracker");

        assertThrows(
                IllegalStateException.class,
                () -> service(props, new CapturingSandbox(null), new GitProviderFactory(List.of(), List.of()))
                        .validateSandboxConfig());
    }
}
