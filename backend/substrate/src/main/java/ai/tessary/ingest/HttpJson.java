// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.errors.UpstreamRateLimitedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small JDK-HttpClient wrapper that returns a Jackson JsonNode or throws an IngestError.
 *
 * <p>Owns the shared retry seam: a transient failure — a 429 rate-limit (honoring
 * the upstream {@code Retry-After}), a 5xx, or a network blip — is retried per the
 * injected {@link RetryPolicy}. Because every source paginates through here, this
 * single point protects all paths (bulk {@code fetch}, preview paging, per-trace
 * {@code fetchOne}, the project lookup) for all providers. Auth (401/403), other
 * 4xx, and malformed responses are not retried — they won't recover.
 */
public final class HttpJson {

    private static final Logger log = LoggerFactory.getLogger(HttpJson.class);

    /** Sleep seam: real runs use {@link Thread#sleep}; tests inject a no-op (or recorder). */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String providerLabel;
    private final RetryPolicy retry;
    private final Sleeper sleeper;

    public HttpJson(String providerLabel, RetryPolicy retry, ObjectMapper mapper) {
        this(
                providerLabel,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                mapper,
                retry,
                Thread::sleep);
    }

    /**
     * Seam for an injected {@link HttpClient} (a custom transport, or a fake that replays canned
     * responses so a source's full pull → paginate → map path can be driven without a live upstream —
     * see {@code LangfuseSourcePullIntegrationTest}). Uses the real {@link Thread#sleep} back-off; tests
     * that exercise retries should pass a {@link RetryPolicy} with a tiny back-off to stay fast.
     */
    public HttpJson(String providerLabel, HttpClient client, RetryPolicy retry, ObjectMapper mapper) {
        this(providerLabel, client, mapper, retry, Thread::sleep);
    }

    HttpJson(String providerLabel, HttpClient client, ObjectMapper mapper, RetryPolicy retry, Sleeper sleeper) {
        this.providerLabel = providerLabel;
        this.client = client;
        this.mapper = mapper;
        this.retry = retry;
        this.sleeper = sleeper;
    }

    public JsonNode get(URI uri, String authHeader) {
        return send(HttpRequest.newBuilder(uri)
                .GET()
                .header("Accept", "application/json")
                .header("Authorization", authHeader)
                .timeout(Duration.ofSeconds(60))
                .build());
    }

    public JsonNode post(URI uri, String authHeader, String body) {
        return send(HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", authHeader)
                .timeout(Duration.ofSeconds(60))
                .build());
    }

    private JsonNode send(HttpRequest req) {
        TessaryException lastTransient = null;
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            Attempt a = attemptOnce(req);
            if (a.body != null) return a.body;
            if (a.fatal != null) throw a.fatal;
            // Transient (429 / 5xx / network): exactly one of body/fatal/transientError holds, so
            // with body and fatal ruled out transientError is non-null. Retry until the budget is spent.
            TessaryException transientError =
                    java.util.Objects.requireNonNull(a.transientError, "Attempt with no outcome");
            lastTransient = transientError;
            if (attempt == retry.maxAttempts()) break;
            long backoff = retry.backoffMs(attempt, a.retryAfterMs);
            log.info(
                    "upstream {} transient failure (attempt {}/{}): {} — backing off {}ms",
                    providerLabel,
                    attempt,
                    retry.maxAttempts(),
                    transientError.getMessage(),
                    backoff);
            if (!backOff(backoff)) break; // interrupted — stop retrying
        }
        // The loop body assigns lastTransient on every non-returning, non-throwing path;
        // the fallback only guards the (unreachable) maxAttempts < 1 case the record forbids.
        throw lastTransient != null
                ? lastTransient
                : new TessaryException(IngestError.UPSTREAM_FAILED, providerLabel, "no response");
    }

    /**
     * Outcome of one HTTP attempt; exactly one of the three states holds: a successful
     * {@code body}, a {@code fatal} error to surface immediately, or a
     * {@code transientError} worth retrying (with {@code retryAfterMs} carrying a 429's hint).
     */
    private record Attempt(
            @Nullable JsonNode body,
            @Nullable TessaryException fatal,
            @Nullable TessaryException transientError,
            long retryAfterMs) {
        static Attempt ok(JsonNode body) {
            return new Attempt(body, null, null, -1);
        }

        static Attempt fatal(TessaryException e) {
            return new Attempt(null, e, null, -1);
        }

        static Attempt transientFailure(TessaryException e, long retryAfterMs) {
            return new Attempt(null, null, e, retryAfterMs);
        }
    }

    private Attempt attemptOnce(HttpRequest req) {
        // Re-validate the destination on every send — DNS rebinding could
        // change the resolved address since the source was created, and we
        // never want a credentialed outbound call hitting RFC1918/IMDS.
        UrlGuard.requirePublicHttp(req.uri().toString());
        HttpResponse<String> res;
        try {
            res = client.send(req, BoundedBody.string());
        } catch (IOException e) {
            // Do not include the exception message in the user-facing error —
            // it can carry the resolved IP / hostname of internal targets. A
            // network blip is transient, so it is worth a retry.
            return Attempt.transientFailure(
                    new TessaryException(IngestError.UPSTREAM_FAILED, e, providerLabel, "network error"), -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Interrupted: do not retry — the caller wants to stop.
            return Attempt.fatal(new TessaryException(IngestError.UPSTREAM_FAILED, e, providerLabel, "network error"));
        }
        int status = res.statusCode();
        if (status == 401 || status == 403) {
            return Attempt.fatal(new TessaryException(IngestError.UPSTREAM_AUTH_FAILED, providerLabel));
        }
        if (status == 429) {
            long retryAfter =
                    parseRetryAfterMs(res.headers().firstValue("Retry-After").orElse(null));
            return Attempt.transientFailure(new UpstreamRateLimitedException(providerLabel, retryAfter), retryAfter);
        }
        if (status >= 500) {
            // Server-side and transient — retry.
            return Attempt.transientFailure(
                    new TessaryException(IngestError.UPSTREAM_FAILED, providerLabel, "HTTP " + status), -1);
        }
        if (status / 100 != 2) {
            // Other 4xx are client errors that won't change on retry. Never echo the
            // upstream body: an SSRF attempt that lands on IMDS or an internal service
            // would otherwise have its response surfaced to the caller. Status is enough.
            return Attempt.fatal(new TessaryException(IngestError.UPSTREAM_FAILED, providerLabel, "HTTP " + status));
        }
        try {
            return Attempt.ok(mapper.readTree(res.body()));
        } catch (Exception e) {
            return Attempt.fatal(
                    new TessaryException(IngestError.UPSTREAM_FAILED, e, providerLabel, "non-JSON response"));
        }
    }

    /** Sleep for the back-off; returns false if interrupted so the caller stops retrying. */
    private boolean backOff(long ms) {
        if (ms <= 0) return true;
        try {
            sleeper.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Parse an HTTP {@code Retry-After} value into milliseconds. The header takes
     * either form per RFC 9110: a non-negative delta-seconds integer
     * ({@code "120"}) or an HTTP-date ({@code "Wed, 21 Oct 2015 07:28:00 GMT"}).
     * Returns a negative value when the header is absent, blank, malformed, or a
     * past date — callers fall back to their own back-off in that case.
     *
     * <p>Package-private so the parsing can be unit-tested without a live 429.
     */
    static long parseRetryAfterMs(@Nullable String header) {
        if (header == null || header.isBlank()) return -1;
        String value = header.trim();
        // delta-seconds form: a plain run of digits. The length guard keeps the
        // *1000 within a long (9 digits ≈ 31 years, far beyond any real hint); a
        // sign or anything longer is not delta-seconds and falls to the date form.
        if (value.length() <= 9 && value.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(value) * 1000L;
        }
        // HTTP-date form: an absolute instant (the value carries its own offset).
        try {
            Instant when = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value));
            long ms = Duration.between(Instant.now(), when).toMillis();
            return ms > 0 ? ms : -1;
        } catch (RuntimeException notHttpDate) {
            return -1;
        }
    }
}
