// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

/**
 * Marks a resolver's out-of-credit exception for {@link ModelProvider#PLATFORM}, so callers say "out of credit" rather
 * than "no key". Only a build that supplies that provider throws it.
 */
public interface PlatformCreditExhausted {}
