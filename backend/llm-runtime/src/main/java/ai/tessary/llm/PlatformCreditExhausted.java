// SPDX-License-Identifier: Apache-2.0
package ai.tessary.llm;

/**
 * Marks the exception a deployment's resolver throws when an org has no credit left for
 * {@link ModelProvider#PLATFORM}. A caller that stops on it tells the org it is out of credit, not
 * that it lacks a key. Nothing in this build throws one: only a build that supplies the provider can
 * run out of it.
 */
public interface PlatformCreditExhausted {}
