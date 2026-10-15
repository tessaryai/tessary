// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

/**
 * A refusal to retry after a delay. HTTP renders it as {@code Retry-After}; OTLP gRPC as {@code UNAVAILABLE} with
 * {@code RetryInfo}, the one shape every stock exporter retries.
 */
public interface Retryable {

    int retryAfterSeconds();
}
