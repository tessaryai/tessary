// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.crypto.SecretBox;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The org's own {@link ProviderCredential}, decrypted and shaped for the sandbox
 * launcher's {@code POST /rca} / {@code POST /triage} — the FULL REMOVAL of the launcher's
 * deployment-env-var credential path (AGENT_PROVIDER and every per-provider key/secret it read)
 * means an agentic run now carries its own credential on the wire, not something the launcher's
 * own process env already had. The database is the only source.
 *
 * <p>Shared by {@code E2bRcaSandbox} and {@code E2bTriageSandbox} — both resolve the project's
 * {@link ModelLane#RCA}/{@link ModelLane#TRIAGE} choice to a {@code (provider, modelId)} pair via
 * {@code ProjectModelSettings#resolveAgenticModel}, then hand that provider to this class to fetch
 * and decrypt the org's credential for it.
 *
 * <p><b>Secret handling is first-class here, not incidental.</b> {@link Credential} carries
 * plaintext secrets in-process only, for exactly as long as it takes to serialize it onto the
 * launcher HTTP request body (over the same Bearer-authed connection every other secret on this
 * path — {@code clone_url}, {@code mcp.token} — already travels). It must never be logged, and the
 * launcher itself never persists it to disk or forwards it to the agent's own prompt/context — see
 * {@code sandbox-runner/launcher/server.js}'s file-header doc and {@code requireCredential} for the
 * other half of that discipline.
 */
@Component
public class AgenticCredentialResolver {

    private final ProviderCredentialRepository repo;
    private final SecretBox secretBox;

    /** The shared, cached {@code projectId → orgId} lookup — see its own javadoc. */
    private final ProjectOrgResolver orgResolver;

    public AgenticCredentialResolver(
            ProviderCredentialRepository repo, SecretBox secretBox, ProjectOrgResolver orgResolver) {
        this.repo = repo;
        this.secretBox = secretBox;
        this.orgResolver = orgResolver;
    }

    /**
     * The wire shape the launcher's {@code credential} request field expects — see that file's
     * header doc for the two variants (Bedrock/mantle vs. every OpenAI-compat provider). Fields
     * irrelevant to {@link #provider} are simply null and dropped by {@code @JsonInclude(NON_NULL)}
     * — the launcher's own {@code requireCredential} validates by provider, so there is no shared
     * "every field always present" contract to keep.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Credential(
            ModelProvider provider,
            @JsonProperty("api_key") @Nullable String apiKey,
            @JsonProperty("base_url") @Nullable String baseUrl,
            @JsonProperty("custom_model_name") @Nullable String customModelName,
            @JsonProperty("aws_region") @Nullable String awsRegion,
            @JsonProperty("aws_access_key") @Nullable String awsAccessKey,
            @JsonProperty("aws_secret_key") @Nullable String awsSecretKey) {}

    /**
     * Resolve, decrypt, and shape the org's credential for {@code provider} — the credential an
     * agentic (RCA/TRIAGE) sandbox run for {@code projectId} should carry. Throws
     * {@link ModelConfigError#MISSING_CREDENTIALS} when the org has none, and
     * {@link ModelConfigError#AGENTIC_IAM_ROLE_UNSUPPORTED} when the org's Bedrock/mantle credential
     * is {@code auth_mode=iam_role}: an E2B microVM cannot assume the
     * operator's own ambient AWS identity, and there is no {@code roleArn}/STS-relay path for it to
     * use instead. IAM-role auth stays usable for the backend's own direct judge calls
     * ({@link ChatModelFactory}); a credential meant to drive a sandbox agent must be
     * {@code api_key} mode.
     */
    public Credential resolve(String projectId, ModelProvider provider) {
        String orgId = orgResolver.orgIdFor(projectId);
        ProviderCredential cred = orgId == null
                ? null
                : repo.findByOrgAndProvider(orgId, provider).orElse(null);
        if (cred == null) {
            throw new TessaryException(ModelConfigError.MISSING_CREDENTIALS, provider);
        }
        boolean bedrock = provider == ModelProvider.BEDROCK || provider == ModelProvider.BEDROCK_MANTLE;
        if (bedrock) {
            if (cred.usesIamRole()) {
                throw new TessaryException(ModelConfigError.AGENTIC_IAM_ROLE_UNSUPPORTED, provider);
            }
            if (cred.awsRegion() == null
                    || cred.awsRegion().isBlank()
                    || cred.awsAccessKeySealed() == null
                    || cred.awsSecretKeySealed() == null) {
                throw new TessaryException(ModelConfigError.MISSING_CREDENTIALS, provider);
            }
            return new Credential(
                    provider,
                    null,
                    null,
                    null,
                    cred.awsRegion().trim(),
                    secretBox.open(cred.awsAccessKeySealed()),
                    secretBox.open(cred.awsSecretKeySealed()));
        }
        if (cred.apiKeySealed() == null || cred.apiKeySealed().isBlank()) {
            throw new TessaryException(ModelConfigError.MISSING_CREDENTIALS, provider);
        }
        return new Credential(
                provider,
                secretBox.open(cred.apiKeySealed()),
                cred.baseUrlOverride(),
                provider == ModelProvider.CUSTOM ? cred.customModelName() : null,
                null,
                null,
                null);
    }
}
