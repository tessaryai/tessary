// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.crypto.SecretBox;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.tenant.Project;
import ai.tessary.tenant.ProjectRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What an agentic (RCA/TRIAGE) run actually carries to the sandbox launcher, now that #939 D4 has
 * removed the launcher's deployment-env-var credential path entirely and the database is the only
 * source. Two wire shapes come out of {@link AgenticCredentialResolver#resolve}, and the fields one
 * shape leaves null are exactly the fields the other populates — so every shape test asserts the
 * ABSENCE of the other shape's fields too, not just the presence of its own. A resolver that filled
 * in both halves would satisfy a presence-only test while sending the launcher a credential its own
 * {@code requireCredential} would reject.
 *
 * <p>The sealed-vs-plaintext distinction is the other thing worth pinning: every secret on this path
 * is AES-GCM ciphertext in the row and plaintext on the wire, so these tests stub {@link SecretBox}
 * to map a recognisably-sealed value to a recognisably-plaintext one and assert the SEALED value is
 * what reached the box. Asserting only that the output is non-null would pass if the resolver
 * forwarded ciphertext to the launcher verbatim.
 */
class AgenticCredentialResolverTest {

    private static final String PROJECT = "p1";
    private static final String ORG = "org1";

    private ProviderCredentialRepository repo;
    private SecretBox secretBox;
    private AgenticCredentialResolver resolver;

    @BeforeEach
    void setUp() {
        repo = mock(ProviderCredentialRepository.class);
        secretBox = mock(SecretBox.class);
        // Deliberately NOT a blanket anyString() stub: each sealed value maps to its own plaintext so
        // a test can tell which ciphertext the resolver actually opened, and an unstubbed value
        // returns null rather than silently looking like a successful decrypt.
        when(secretBox.open("sealed-api-key")).thenReturn("plain-api-key");
        when(secretBox.open("sealed-access")).thenReturn("plain-access");
        when(secretBox.open("sealed-secret")).thenReturn("plain-secret");
        resolver = new AgenticCredentialResolver(repo, secretBox, new ProjectOrgResolver(projectRepo()));
    }

    /** projectId → orgId, the shared cached lookup ChatModelFactory and this resolver both use. */
    private static ProjectRepository projectRepo() {
        ProjectRepository p = mock(ProjectRepository.class);
        when(p.findById(PROJECT))
                .thenReturn(Optional.of(new Project(PROJECT, ORG, "s", "n", null, "t", null, null, false, null)));
        when(p.findById("unknown-project")).thenReturn(Optional.empty());
        return p;
    }

    private ProviderCredential cred(
            ModelProvider provider,
            String apiKeySealed,
            String baseUrlOverride,
            String awsRegion,
            String awsAccessSealed,
            String awsSecretSealed,
            String customModelName,
            String authMode) {
        return new ProviderCredential(
                "pc_1",
                ORG,
                PROJECT,
                provider,
                baseUrlOverride,
                apiKeySealed,
                awsRegion,
                awsAccessSealed,
                awsSecretSealed,
                null,
                customModelName,
                authMode,
                "t",
                "t");
    }

    private ProviderCredential apiKeyCred(ModelProvider provider, String baseUrl, String customModelName) {
        return cred(
                provider,
                "sealed-api-key",
                baseUrl,
                null,
                null,
                null,
                customModelName,
                ProviderCredential.AUTH_MODE_API_KEY);
    }

    private ProviderCredential bedrockCred(ModelProvider provider, String region, String access, String secret) {
        return cred(provider, null, null, region, access, secret, null, ProviderCredential.AUTH_MODE_API_KEY);
    }

    private void stored(ModelProvider provider, ProviderCredential c) {
        when(repo.findByOrgAndProvider(ORG, provider)).thenReturn(Optional.of(c));
    }

    private ModelConfigError errorFrom(ModelProvider provider) {
        return (ModelConfigError) assertThrows(TessaryException.class, () -> resolver.resolve(PROJECT, provider))
                .error();
    }

    // ---------------------------------------------------------------- missing credentials

    @Test
    void noCredentialForTheProvider_failsWithMissingCredentials() {
        when(repo.findByOrgAndProvider(ORG, ModelProvider.GEMINI)).thenReturn(Optional.empty());
        assertEquals(ModelConfigError.MISSING_CREDENTIALS, errorFrom(ModelProvider.GEMINI));
    }

    /**
     * An unresolvable project yields a null orgId, which must fail closed the same way an org with no
     * row does — never fall through to a repository lookup on a null org.
     */
    @Test
    void unknownProject_failsWithMissingCredentialsAndNeverQueriesTheRepository() {
        TessaryException ex =
                assertThrows(TessaryException.class, () -> resolver.resolve("unknown-project", ModelProvider.OPENAI));
        assertEquals(ModelConfigError.MISSING_CREDENTIALS, ex.error());
        verify(repo, never()).findByOrgAndProvider(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void openAiCompatCredentialWithNoApiKey_failsWithMissingCredentials() {
        stored(
                ModelProvider.GROK,
                cred(ModelProvider.GROK, null, null, null, null, null, null, ProviderCredential.AUTH_MODE_API_KEY));
        assertEquals(ModelConfigError.MISSING_CREDENTIALS, errorFrom(ModelProvider.GROK));
    }

    @Test
    void openAiCompatCredentialWithBlankApiKey_failsWithMissingCredentials() {
        stored(
                ModelProvider.GLM,
                cred(ModelProvider.GLM, "   ", null, null, null, null, null, ProviderCredential.AUTH_MODE_API_KEY));
        assertEquals(ModelConfigError.MISSING_CREDENTIALS, errorFrom(ModelProvider.GLM));
    }

    @Test
    void bedrockWithoutRegion_failsWithMissingCredentials() {
        stored(ModelProvider.BEDROCK, bedrockCred(ModelProvider.BEDROCK, null, "sealed-access", "sealed-secret"));
        assertEquals(ModelConfigError.MISSING_CREDENTIALS, errorFrom(ModelProvider.BEDROCK));
    }

    @Test
    void bedrockWithBlankRegion_failsWithMissingCredentials() {
        stored(ModelProvider.BEDROCK, bedrockCred(ModelProvider.BEDROCK, "  ", "sealed-access", "sealed-secret"));
        assertEquals(ModelConfigError.MISSING_CREDENTIALS, errorFrom(ModelProvider.BEDROCK));
    }

    @Test
    void bedrockWithoutAccessKey_failsWithMissingCredentials() {
        stored(ModelProvider.BEDROCK, bedrockCred(ModelProvider.BEDROCK, "us-east-1", null, "sealed-secret"));
        assertEquals(ModelConfigError.MISSING_CREDENTIALS, errorFrom(ModelProvider.BEDROCK));
    }

    @Test
    void bedrockWithoutSecretKey_failsWithMissingCredentials() {
        stored(ModelProvider.BEDROCK, bedrockCred(ModelProvider.BEDROCK, "us-east-1", "sealed-access", null));
        assertEquals(ModelConfigError.MISSING_CREDENTIALS, errorFrom(ModelProvider.BEDROCK));
    }

    @Test
    void mantleWithoutAwsKeys_failsWithMissingCredentials() {
        stored(ModelProvider.BEDROCK_MANTLE, bedrockCred(ModelProvider.BEDROCK_MANTLE, "us-east-1", null, null));
        assertEquals(ModelConfigError.MISSING_CREDENTIALS, errorFrom(ModelProvider.BEDROCK_MANTLE));
    }

    // ---------------------------------------------------------------- IAM role is not sandbox-usable

    /**
     * IAM-role auth stays valid for the backend's own direct judge calls, but an E2B microVM cannot
     * assume the operator's ambient AWS identity and there is no STS-relay path for it — so this is a
     * distinct, named error rather than MISSING_CREDENTIALS, which would read as "add a key" when the
     * org has deliberately configured one.
     */
    @Test
    void bedrockIamRoleCredential_failsWithAgenticIamRoleUnsupported() {
        stored(
                ModelProvider.BEDROCK,
                cred(
                        ModelProvider.BEDROCK,
                        null,
                        null,
                        "us-east-1",
                        "sealed-access",
                        "sealed-secret",
                        null,
                        ProviderCredential.AUTH_MODE_IAM_ROLE));
        assertEquals(ModelConfigError.AGENTIC_IAM_ROLE_UNSUPPORTED, errorFrom(ModelProvider.BEDROCK));
    }

    @Test
    void mantleIamRoleCredential_failsWithAgenticIamRoleUnsupported() {
        stored(
                ModelProvider.BEDROCK_MANTLE,
                cred(
                        ModelProvider.BEDROCK_MANTLE,
                        null,
                        null,
                        "us-east-1",
                        "sealed-access",
                        "sealed-secret",
                        null,
                        ProviderCredential.AUTH_MODE_IAM_ROLE));
        assertEquals(ModelConfigError.AGENTIC_IAM_ROLE_UNSUPPORTED, errorFrom(ModelProvider.BEDROCK_MANTLE));
    }

    /**
     * The IAM refusal is checked BEFORE the AWS-key completeness check, so an IAM-role row with no
     * stored keys — the normal shape of one, since the whole point is that it has none — reports why
     * it cannot drive a sandbox rather than telling the operator to add keys it deliberately omitted.
     */
    @Test
    void iamRoleCredentialWithNoKeys_reportsIamRoleNotMissingCredentials() {
        stored(
                ModelProvider.BEDROCK,
                cred(ModelProvider.BEDROCK, null, null, null, null, null, null, ProviderCredential.AUTH_MODE_IAM_ROLE));
        assertEquals(ModelConfigError.AGENTIC_IAM_ROLE_UNSUPPORTED, errorFrom(ModelProvider.BEDROCK));
    }

    // ---------------------------------------------------------------- the Bedrock/mantle wire shape

    @Test
    void bedrockShape_carriesDecryptedAwsKeysAndNoOpenAiCompatFields() {
        stored(
                ModelProvider.BEDROCK,
                bedrockCred(ModelProvider.BEDROCK, "us-east-1", "sealed-access", "sealed-secret"));

        AgenticCredentialResolver.Credential c = resolver.resolve(PROJECT, ModelProvider.BEDROCK);

        assertEquals(ModelProvider.BEDROCK, c.provider());
        assertEquals("us-east-1", c.awsRegion());
        assertEquals("plain-access", c.awsAccessKey());
        assertEquals("plain-secret", c.awsSecretKey());
        assertNull(c.apiKey(), "a Bedrock credential carries no api_key");
        assertNull(c.baseUrl(), "a Bedrock credential carries no base_url");
        assertNull(c.customModelName(), "a Bedrock credential carries no custom_model_name");

        // The sealed ciphertext is what reaches the box — not the row, and not the plaintext.
        verify(secretBox).open("sealed-access");
        verify(secretBox).open("sealed-secret");
    }

    @Test
    void bedrockShape_trimsTheStoredRegion() {
        stored(
                ModelProvider.BEDROCK,
                bedrockCred(ModelProvider.BEDROCK, "  eu-west-1  ", "sealed-access", "sealed-secret"));
        assertEquals(
                "eu-west-1", resolver.resolve(PROJECT, ModelProvider.BEDROCK).awsRegion());
    }

    @Test
    void mantleShape_isTheSameBedrockShape() {
        stored(
                ModelProvider.BEDROCK_MANTLE,
                bedrockCred(ModelProvider.BEDROCK_MANTLE, "us-east-1", "sealed-access", "sealed-secret"));

        AgenticCredentialResolver.Credential c = resolver.resolve(PROJECT, ModelProvider.BEDROCK_MANTLE);

        assertEquals(ModelProvider.BEDROCK_MANTLE, c.provider());
        assertEquals("plain-access", c.awsAccessKey());
        assertEquals("plain-secret", c.awsSecretKey());
        assertNull(c.apiKey());
    }

    // ---------------------------------------------------------------- the OpenAI-compat wire shape

    /**
     * Every non-Bedrock provider takes the same branch, so this walks all of them rather than
     * asserting one and assuming the rest — the branch is selected by an inequality, and a provider
     * accidentally added to the Bedrock side would otherwise go unnoticed.
     */
    @Test
    void openAiCompatShape_carriesDecryptedApiKeyAndNoAwsFields() {
        for (ModelProvider provider : List.of(
                ModelProvider.OPENAI,
                ModelProvider.MOONSHOT,
                ModelProvider.GEMINI,
                ModelProvider.GLM,
                ModelProvider.GROK)) {
            stored(provider, apiKeyCred(provider, "https://example.test/v1", null));

            AgenticCredentialResolver.Credential c = resolver.resolve(PROJECT, provider);

            assertEquals(provider, c.provider());
            assertEquals("plain-api-key", c.apiKey(), provider + " must carry the DECRYPTED key");
            assertEquals("https://example.test/v1", c.baseUrl(), provider + " must carry its base_url override");
            assertNull(c.awsRegion(), provider + " must carry no aws_region");
            assertNull(c.awsAccessKey(), provider + " must carry no aws_access_key");
            assertNull(c.awsSecretKey(), provider + " must carry no aws_secret_key");
        }
    }

    @Test
    void openAiCompatShape_passesANullBaseUrlThroughUntouched() {
        stored(ModelProvider.OPENAI, apiKeyCred(ModelProvider.OPENAI, null, null));
        assertNull(resolver.resolve(PROJECT, ModelProvider.OPENAI).baseUrl());
    }

    // ---------------------------------------------------------------- CUSTOM's extra field

    /**
     * custom_model_name is the only field CUSTOM has that no other provider does — an arbitrary
     * OpenAI-compatible endpoint has no catalog to name a model from, so the row carries it.
     */
    @Test
    void customProvider_carriesItsCustomModelName() {
        stored(ModelProvider.CUSTOM, apiKeyCred(ModelProvider.CUSTOM, "https://vllm.internal/v1", "llama-3.3-70b"));

        AgenticCredentialResolver.Credential c = resolver.resolve(PROJECT, ModelProvider.CUSTOM);

        assertEquals("llama-3.3-70b", c.customModelName());
        assertEquals("plain-api-key", c.apiKey());
        assertEquals("https://vllm.internal/v1", c.baseUrl());
    }

    /**
     * The field is meaningless for every other provider, so a row that happens to carry one (a
     * leftover from a provider switch, say) must not leak it onto the wire.
     */
    @Test
    void nonCustomProvider_dropsAnyStoredCustomModelName() {
        for (ModelProvider provider : List.of(
                ModelProvider.OPENAI,
                ModelProvider.MOONSHOT,
                ModelProvider.GEMINI,
                ModelProvider.GLM,
                ModelProvider.GROK)) {
            stored(provider, apiKeyCred(provider, null, "leftover-model-id"));
            assertNull(
                    resolver.resolve(PROJECT, provider).customModelName(),
                    provider + " must not carry custom_model_name");
        }
    }
}
