// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

/**
 * Published in-process when an org saves a provider credential, so a classifier paused for want of a
 * working key can try again without {@code llm/} knowing classifiers exist (the same shape as {@code
 * CallSiteFactChangedEvent}). A consumer must be idempotent and non-fatal: the credential is already
 * stored.
 */
public record ProviderCredentialSavedEvent(String orgId, ModelProvider provider) {}
