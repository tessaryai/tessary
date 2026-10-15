// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

/**
 * A refusal that repeats until a person changes something (a key, a balance, a setting). Work that hits one is held and
 * re-checked rather than retried on a backoff; compare {@link Retryable}. Nothing here throws one, but Tessary Cloud's
 * out-of-credit refusal does, and triage relies on it to hold rather than dead-letter.
 */
public interface AwaitsConfiguration {}
