// SPDX-License-Identifier: Apache-2.0
package ai.tessary.open.errors;

/**
 * A 429 from an upstream provider, optionally carrying the {@code Retry-After}
 * hint (in milliseconds) the provider returned. Callers that retry — notably
 * {@link ai.tessary.run.TraceHydrator} — back off for that long instead of
 * guessing, which matters for per-minute buckets where a sub-second retry is
 * guaranteed to land inside the same rate-limited window.
 *
 * <p>It keeps {@link IngestError#UPSTREAM_RATE_LIMITED} as its error code, so the
 * global error handler and any {@code error()}-based branching treat it exactly
 * like the plain {@link TessaryException} it replaces.
 */
public class UpstreamRateLimitedException extends TessaryException {

    /** Upstream-supplied back-off in ms, or a negative value when no usable hint was given. */
    private final long retryAfterMs;

    public UpstreamRateLimitedException(String provider, long retryAfterMs) {
        super(IngestError.UPSTREAM_RATE_LIMITED, provider);
        this.retryAfterMs = retryAfterMs;
    }

    /** Upstream-supplied back-off in ms, or a negative value when the provider gave no hint. */
    public long retryAfterMs() {
        return retryAfterMs;
    }
}
