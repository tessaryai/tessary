// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * An org's sealed credential for one LLM provider, unique per {@code (org_id, provider)} and shared by every project.
 * {@code projectId} is an audit trail from the org-scope migration and is never read for lookup. The wire form only
 * exposes {@code has_*} flags. {@code bedrockModelArn} lives here because inference-profile ARNs are account-scoped.
 */
public record ProviderCredential(
        String id,
        @JsonProperty("org_id") String orgId,
        @JsonProperty("project_id") @Nullable String projectId,
        ModelProvider provider,
        @JsonProperty("base_url_override") String baseUrlOverride,
        @JsonProperty("api_key_sealed") String apiKeySealed,
        @JsonProperty("aws_region") String awsRegion,
        @JsonProperty("aws_access_key_sealed") String awsAccessKeySealed,
        @JsonProperty("aws_secret_key_sealed") String awsSecretKeySealed,
        @JsonProperty("bedrock_model_arn") String bedrockModelArn,
        /**
         * The real model id for {@link ModelProvider#CUSTOM}, whose catalog entry is only representative; never read
         * for other providers.
         */
        @JsonProperty("custom_model_name") String customModelName,
        /**
         * {@link #AUTH_MODE_IAM_ROLE} is Bedrock/mantle only and an explicit opt-in: null keys never fall back to the
         * ambient {@code DefaultCredentialsProvider}, which would silently bill the platform.
         */
        @JsonProperty("auth_mode") String authMode,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {

    public static final String AUTH_MODE_API_KEY = "api_key";
    public static final String AUTH_MODE_IAM_ROLE = "iam_role";

    public boolean usesIamRole() {
        return AUTH_MODE_IAM_ROLE.equals(authMode);
    }
}
