// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

/**
 * Published when an org saves a provider credential, so a classifier paused for a key can retry without {@code llm/}
 * knowing classifiers exist. Consumers must be idempotent and non-fatal.
 */
public record ProviderCredentialSavedEvent(String orgId, ModelProvider provider) {}
