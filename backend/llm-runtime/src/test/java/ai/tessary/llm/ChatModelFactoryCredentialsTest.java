// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.tessary.crypto.SecretBox;
import ai.tessary.llm.catalog.ModelCatalogFetchService;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Credential policy: every provider requires an org-level {@link ProviderCredential}.
 * Credentials are org-keyed, and there is no ambient-key fallback: Ollama, the platform's one
 * credential-free exception, was removed. Resolution is by {@code (provider, modelName,
 * effort)}; the key comes from the project's ORG credential for that provider.
 */
class ChatModelFactoryCredentialsTest {

    private static final String PROJECT = "p1";
    private static final String ORG = "org1";

    private ProviderCredentialRepository repo;
    private SecretBox secretBox;
    /** No project has opted a lane in here, so every resolve falls through to the platform default. */
    private ProjectModelSettings settings;

    private ChatModelFactory factory;

    @BeforeEach
    void setUp() {
        repo = mock(ProviderCredentialRepository.class);
        secretBox = mock(SecretBox.class);
        when(secretBox.open(anyString())).thenReturn("decrypted-key");
        ProviderCredentialRepository settingsCredRepo = mock(ProviderCredentialRepository.class);
        // Not exercised here: every resolve() in this test names a Bedrock/mantle model, which
        // short-circuits before either mock is touched. Mocked purely to satisfy the constructor.
        settings = new ProjectModelSettings(
                noSettings(), settingsCredRepo, mock(ProjectOrgResolver.class), mock(ModelCatalogFetchService.class));
        factory = new ChatModelFactory(
                repo,
                secretBox,
                new ProjectOrgResolver(projectRepo()),
                settings,
                mock(ModelCatalogFetchService.class),
                "5m");
    }

    /** projectId → orgId lookup, wrapped in the shared cache both ChatModelFactory and AgenticCredentialResolver use. */
    private static ProjectRepository projectRepo() {
        ProjectRepository p = mock(ProjectRepository.class);
        when(p.findById(PROJECT))
                .thenReturn(Optional.of(new Project(PROJECT, ORG, "s", "n", null, "t", null, null, false, null)));
        return p;
    }

    /** A repository that returns no per-lane rows: the state every existing project is in. */
    private static ProjectModelSettingRepository noSettings() {
        ProjectModelSettingRepository r = mock(ProjectModelSettingRepository.class);
        when(r.findByProject(anyString())).thenReturn(java.util.List.of());
        return r;
    }

    private ProviderCredential cred(
            ModelProvider provider,
            String apiKeySealed,
            String awsRegion,
            String awsAccessSealed,
            String awsSecretSealed) {
        return cred(
                provider,
                apiKeySealed,
                awsRegion,
                awsAccessSealed,
                awsSecretSealed,
                ProviderCredential.AUTH_MODE_API_KEY);
    }

    private ProviderCredential cred(
            ModelProvider provider,
            String apiKeySealed,
            String awsRegion,
            String awsAccessSealed,
            String awsSecretSealed,
            String authMode) {
        return new ProviderCredential(
                "pc_1",
                ORG,
                PROJECT,
                provider,
                null,
                apiKeySealed,
                awsRegion,
                awsAccessSealed,
                awsSecretSealed,
                null,
                null,
                authMode,
                "t",
                "t");
    }

    private void noCredential(ModelProvider provider) {
        when(repo.findByOrgAndProvider(ORG, provider)).thenReturn(Optional.empty());
    }

    @Test
    void paidOpenAiWithoutCredential_failsWithMissingCredentials() {
        noCredential(ModelProvider.OPENAI);
        TessaryException ex = assertThrows(
                TessaryException.class, () -> factory.resolve(PROJECT, ModelProvider.OPENAI, "gpt-5.5", null));
        assertEquals(ModelConfigError.MISSING_CREDENTIALS, ex.error());
    }

    @Test
    void paidAnthropicWithoutCredential_failsWithMissingCredentials() {
        noCredential(ModelProvider.ANTHROPIC);
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> factory.resolve(PROJECT, ModelProvider.ANTHROPIC, "claude-sonnet-4-6", null));
        assertEquals(ModelConfigError.MISSING_CREDENTIALS, ex.error());
    }

    @Test
    void bedrockWithRegionButNoAwsKeys_failsWithMissingCredentials() {
        when(repo.findByOrgAndProvider(ORG, ModelProvider.BEDROCK))
                .thenReturn(Optional.of(cred(ModelProvider.BEDROCK, null, "us-east-1", null, null)));
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> factory.resolve(PROJECT, ModelProvider.BEDROCK, "anthropic.claude-sonnet-4-6", null));
        assertEquals(ModelConfigError.MISSING_CREDENTIALS, ex.error());
    }

    /**
     * The null-key refusal above must still hold with the {@code auth_mode} column present and
     * defaulted: an explicit opt-in is required, not merely the column existing.
     */
    @Test
    void bedrockDefaultAuthModeWithNoAwsKeys_stillFailsWithMissingCredentials() {
        when(repo.findByOrgAndProvider(ORG, ModelProvider.BEDROCK))
                .thenReturn(Optional.of(cred(
                        ModelProvider.BEDROCK, null, "us-east-1", null, null, ProviderCredential.AUTH_MODE_API_KEY)));
        TessaryException ex = assertThrows(
                TessaryException.class,
                () -> factory.resolve(PROJECT, ModelProvider.BEDROCK, "anthropic.claude-sonnet-4-6", null));
        assertEquals(ModelConfigError.MISSING_CREDENTIALS, ex.error());
    }

    /**
     * A credential that has explicitly opted into {@code iam_role} builds successfully with no
     * sealed AWS keys at all: the opt-in, not the absence of keys, is what unlocks the ambient
     * {@code DefaultCredentialsProvider} path.
     */
    @Test
    void bedrockIamRoleAuthWithNoAwsKeys_builds() {
        when(repo.findByOrgAndProvider(ORG, ModelProvider.BEDROCK))
                .thenReturn(Optional.of(cred(
                        ModelProvider.BEDROCK, null, "us-east-1", null, null, ProviderCredential.AUTH_MODE_IAM_ROLE)));
        assertNotNull(factory.resolve(PROJECT, ModelProvider.BEDROCK, "anthropic.claude-sonnet-4-6", null));
    }

    @Test
    void openAiReasoningModelWithKeyAndEffort_builds() {
        when(repo.findByOrgAndProvider(ORG, ModelProvider.OPENAI))
                .thenReturn(Optional.of(cred(ModelProvider.OPENAI, "sealed-key", null, null, null)));
        // gpt-5.5 supports effort; with a key it builds with the effort baked in.
        var resolved = factory.resolve(PROJECT, ModelProvider.OPENAI, "gpt-5.5", "high");
        assertNotNull(resolved);
        assertEquals("gpt-5.5", resolved.modelName());
    }

    /** A model not in the catalog throws UNKNOWN_MODEL: there is no platform default left to fall
     *  back to. */
    @Test
    void danglingModelSelection_throwsUnknownModel() {
        TessaryException ex = assertThrows(
                TessaryException.class, () -> factory.resolve(PROJECT, ModelProvider.OPENAI, "no-such-model", null));
        assertEquals(ModelConfigError.UNKNOWN_MODEL, ex.error());
    }

    // ---- The unprofiled-Bedrock-model WARN: a resolve for a model with no BedrockModelProfile
    // entry logs exactly once. ----

    /** A model IN {@link BedrockModelProfile#PROFILES} must never trip the unprofiled-model WARN:
     *  it is only for the gap case, not every Bedrock resolve. */
    @Test
    void aProfiledBedrockModel_resolvesWithNoUnprofiledWarning() {
        when(repo.findByOrgAndProvider(ORG, ModelProvider.BEDROCK))
                .thenReturn(Optional.of(cred(
                        ModelProvider.BEDROCK, null, "us-east-1", null, null, ProviderCredential.AUTH_MODE_IAM_ROLE)));
        ch.qos.logback.classic.Logger factoryLog =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ChatModelFactory.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events =
                new ch.qos.logback.core.read.ListAppender<>();
        events.start();
        factoryLog.addAppender(events);
        try {
            assertNotNull(factory.resolve(PROJECT, ModelProvider.BEDROCK, "anthropic.claude-sonnet-5", null));
        } finally {
            factoryLog.detachAppender(events);
            events.stop();
        }

        assertEquals(
                0,
                events.list.stream()
                        .filter(e -> e.getFormattedMessage().contains("no BedrockModelProfile entry"))
                        .count(),
                "claude-sonnet-5 is a real BedrockModelProfile.PROFILES entry — no WARN is warranted");
    }

    /**
     * A Bedrock model that resolves with no matching {@link BedrockModelProfile} entry logs
     * exactly one WARN naming the model, and a second resolve of the same model does not repeat
     * it (latched per key, like {@code SopCompileWorker}'s no-compiler WARN).
     */
    @Test
    void anUnprofiledBedrockModel_warnsExactlyOncePerModelKey() {
        when(repo.findByOrgAndProvider(ORG, ModelProvider.BEDROCK))
                .thenReturn(Optional.of(cred(
                        ModelProvider.BEDROCK, null, "us-east-1", null, null, ProviderCredential.AUTH_MODE_IAM_ROLE)));
        ch.qos.logback.classic.Logger factoryLog =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ChatModelFactory.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> events =
                new ch.qos.logback.core.read.ListAppender<>();
        events.start();
        factoryLog.addAppender(events);
        try {
            // "claude-sonnet-4-6" is a real ModelCatalog entry with no matching BedrockModelProfile
            // row: resolve()'s cache is keyed by (provider, modelName, effort), so both calls below
            // hit the same cacheParamsFor(...) call regardless of the ChatModel cache.
            assertNotNull(factory.resolve(PROJECT, ModelProvider.BEDROCK, "anthropic.claude-sonnet-4-6", null));
            assertNotNull(factory.resolve(PROJECT, ModelProvider.BEDROCK, "anthropic.claude-sonnet-4-6", null));
        } finally {
            factoryLog.detachAppender(events);
            events.stop();
        }

        var unprofiledWarnings = events.list.stream()
                .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("no BedrockModelProfile entry"))
                .toList();
        assertEquals(
                1,
                unprofiledWarnings.size(),
                "two resolves of the same unprofiled model must log ONE warning, not one per call");
        assertTrue(unprofiledWarnings.get(0).getFormattedMessage().contains("anthropic.claude-sonnet-4-6"));
    }
}
