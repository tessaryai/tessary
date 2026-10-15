// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm.catalog;

import org.jspecify.annotations.Nullable;

/**
 * Decrypted materials a {@link ProviderModelLister} needs, built by {@code ModelCatalogFetchService} so no lister
 * touches ciphertext. Not {@code AgenticCredentialResolver.Credential}, which rejects {@code iam_role} because an E2B
 * microVM cannot assume the ambient AWS identity; listing runs backend-side, where {@link #usesIamRole} selects the
 * ambient provider.
 */
public record ResolvedCredential(
        @Nullable String apiKey,
        @Nullable String baseUrl,
        @Nullable String awsRegion,
        @Nullable String awsAccessKey,
        @Nullable String awsSecretKey,
        boolean usesIamRole) {}
