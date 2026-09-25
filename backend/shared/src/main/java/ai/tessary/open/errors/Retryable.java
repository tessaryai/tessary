// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

/**
 * A refusal the client should retry after a delay, as opposed to one it must not repeat. The HTTP
 * edge renders it as {@code Retry-After}; the OTLP gRPC edge as {@code UNAVAILABLE} carrying
 * {@code RetryInfo}, the one shape every stock exporter retries.
 */
public interface Retryable {

    int retryAfterSeconds();
}
