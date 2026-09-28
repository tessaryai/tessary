// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

/**
 * A refusal that will repeat, unchanged, until a person changes something: adds a key, tops up a
 * balance, fixes a setting. Work that hits one is held and re-checked later rather than retried on a
 * backoff, which would spend every attempt on an answer that cannot change. The counterpart of
 * {@link Retryable}, which is a refusal that clears on its own after a delay.
 */
public interface AwaitsConfiguration {}
