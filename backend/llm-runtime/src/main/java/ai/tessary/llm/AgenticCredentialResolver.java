// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import ai.tessary.crypto.SecretBox;
import ai.tessary.open.errors.ModelConfigError;
import ai.tessary.open.errors.TessaryException;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * The org's own {@link ProviderCredential}, decrypted for the sandbox launcher; the database is the only source.
 * {@link Credential} holds plaintext secrets in-process only until serialized onto the launcher request: never log it.
 * Another build can extend this to resolve {@link ModelProvider#PLATFORM}.
 */
public class AgenticCredentialResolver {

    private final ProviderCredentialRepository repo;
    private final SecretBox secretBox;

    private final ProjectOrgResolver orgResolver;

    public AgenticCredentialResolver(
            ProviderCredentialRepository repo, SecretBox secretBox, ProjectOrgResolver orgResolver) {
        this.repo = repo;
        this.secretBox = secretBox;
        this.orgResolver = orgResolver;
    }

    /**
     * The launcher's {@code credential} wire shape; fields irrelevant to {@link #provider} are null and dropped.
     * {@code egressSecret} names a secret the sandbox egress proxy injects in place of {@code apiKey}, so the key never
     * enters the VM; {@code lease} never goes on the wire.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Credential(
            ModelProvider provider,
            @JsonProperty("api_key") @Nullable String apiKey,
            @JsonProperty("base_url") @Nullable String baseUrl,
            @JsonProperty("custom_model_name") @Nullable String customModelName,
            @JsonProperty("aws_region") @Nullable String awsRegion,
            @JsonProperty("aws_access_key") @Nullable String awsAccessKey,
            @JsonProperty("aws_secret_key") @Nullable String awsSecretKey,
            @JsonProperty("platform_funded") boolean platformFunded,
            @JsonProperty("egress_secret") @Nullable String egressSecret,
            @JsonIgnore @Nullable String lease) {

        public Credential(
                ModelProvider provider,
                @Nullable String apiKey,
                @Nullable String baseUrl,
                @Nullable String customModelName,
                @Nullable String awsRegion,
                @Nullable String awsAccessKey,
                @Nullable String awsSecretKey) {
            this(provider, apiKey, baseUrl, customModelName, awsRegion, awsAccessKey, awsSecretKey, false, null, null);
        }
    }

    /**
     * Throws {@link ModelConfigError#MISSING_CREDENTIALS} when the org has none, and
     * {@link ModelConfigError#AGENTIC_IAM_ROLE_UNSUPPORTED} for an {@code iam_role} Bedrock/mantle credential: an E2B
     * microVM cannot assume the operator's AWS identity.
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

    /**
     * Called once the run ends, however it ended. A build that reserves something per run frees it here via
     * {@link Credential#lease()}.
     */
    public void release(Credential credential) {
    }
}
