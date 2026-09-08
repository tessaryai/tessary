// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.llm.catalog;

import org.jspecify.annotations.Nullable;

/**
 * The decrypted materials a {@link ProviderModelLister} needs to call its provider's own models
 * endpoint — built by {@code ModelCatalogFetchService} from the org's sealed {@code
 * ProviderCredential} row plus {@code SecretBox}, so no lister ever imports the crypto dependency or
 * touches ciphertext directly.
 *
 * <p><b>Deliberately not the same type as {@code AgenticCredentialResolver.Credential}</b>, despite
 * carrying nearly the same fields: that type's {@code resolve()} rejects {@code auth_mode=iam_role}
 * because an E2B microVM cannot assume the operator's ambient AWS identity. Listing runs backend-side
 * — the same trust boundary {@code ChatModelFactory}'s own generation calls run in — so an
 * IAM-role-authenticated Bedrock/mantle credential is perfectly usable here; {@link #usesIamRole}
 * exists so {@code BedrockModelLister}/{@code BedrockMantleModelLister} can build the ambient
 * {@code DefaultCredentialsProvider} instead of static keys, exactly like {@code ChatModelFactory}
 * already does for generation. Reusing the agentic type would have meant either silently dropping
 * that distinction or forking its rejection logic — a new, narrower type was the smaller change.
 */
public record ResolvedCredential(
        @Nullable String apiKey,
        @Nullable String baseUrl,
        @Nullable String awsRegion,
        @Nullable String awsAccessKey,
        @Nullable String awsSecretKey,
        boolean usesIamRole) {}
