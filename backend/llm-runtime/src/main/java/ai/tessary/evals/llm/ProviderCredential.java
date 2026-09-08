// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * An organization's stored credential for one LLM provider — one row per {@code (org_id, provider)}
 * (enforced by a unique index). Every project in the org shares it: there is no per-project override
 * and no per-environment variant (#939 D1). The credential is provider-scoped, not model-scoped: an
 * org adds a key for a provider once and every project may then judge with any catalog model that
 * provider hosts (see {@link ModelCatalog}).
 *
 * <p>{@code projectId} is historical only, as of #939 D1 (migration {@code 0020}): credentials were
 * originally keyed {@code (project_id, provider)}, and the org-scope migration backfilled {@code
 * org_id} from each row's project, collapsing to one row per {@code (org_id, provider)} where a
 * customer had saved the same provider under two projects (last-write-wins by {@code updated_at}) and
 * leaving {@code project_id} in place as an audit trail of which project the surviving row came from.
 * Nothing reads it for authorization or lookup any more — {@code org_id} is the key.
 *
 * <p>Credential fields hold AES-GCM-sealed ciphertext (via {@link ai.tessary.evals.crypto.SecretBox}).
 * Every provider requires user-provided credentials — there is no platform-funded, credential-free
 * platform any more (Ollama, the one exception, was removed by #939 D6's maker filter) — and a run
 * fails with {@code MISSING_CREDENTIALS} when the org has none (see {@code
 * ChatModelFactory#resolveApiKey} / {@code buildBedrock}). The wire form never carries decrypted
 * secrets — the controller projects to a "redacted" view that just exposes boolean {@code has_*}
 * flags. {@code bedrockModelArn} stays here because inference-profile ARNs are AWS-account-scoped, so
 * they live with the account credentials rather than the catalog.
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
         * The free-text model id a {@link ModelProvider#CUSTOM} credential names. {@link ModelCatalog}
         * carries one representative CUSTOM entry (there is no fixed catalog for an arbitrary
         * OpenAI-compatible endpoint), so this is where the real model id a project wants actually
         * lives; {@code ChatModelFactory#buildOpenAiCompat} prefers it over the catalog entry's own
         * name whenever the provider is CUSTOM and this is set. Meaningless (and never read) for
         * every other provider.
         */
        @JsonProperty("custom_model_name") String customModelName,
        /**
         * {@link #AUTH_MODE_API_KEY} (the default, every row written before this field existed) or
         * {@link #AUTH_MODE_IAM_ROLE} — Bedrock/{@code BEDROCK_MANTLE} only. An explicit, user-set
         * opt-in: {@code ChatModelFactory#buildBedrock}/{@code buildMantle} refuse to fall back to the
         * ambient {@code DefaultCredentialsProvider} just because the sealed AWS keys are null (that
         * refusal is what stops a customer's run from silently billing the platform, #1050), so IAM-role
         * auth is reachable only when a project's own stored row says so — never inferred from absent
         * keys.
         */
        @JsonProperty("auth_mode") String authMode,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt) {

    public static final String AUTH_MODE_API_KEY = "api_key";
    public static final String AUTH_MODE_IAM_ROLE = "iam_role";

    /** Whether this row has opted a Bedrock/mantle credential into ambient IAM-role auth. */
    public boolean usesIamRole() {
        return AUTH_MODE_IAM_ROLE.equals(authMode);
    }
}
