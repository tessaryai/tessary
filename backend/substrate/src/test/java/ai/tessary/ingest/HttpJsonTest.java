// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.open.errors.IngestError;
import ai.tessary.open.errors.TessaryException;
import ai.tessary.open.errors.UpstreamRateLimitedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class HttpJsonTest {

    // A public IP literal: passes UrlGuard without a real DNS lookup (203.0.113.0/24 is
    // TEST-NET-3, reserved for documentation — not loopback/link-local/RFC1918).
    private static final URI URI_OK = URI.create("https://203.0.113.7/api");

    // ---- Retry-After parsing ----

    @Test
    void parsesDeltaSecondsForm() {
        assertEquals(120_000, HttpJson.parseRetryAfterMs("120"));
        assertEquals(0, HttpJson.parseRetryAfterMs("0"), "zero seconds is a valid 'retry now'");
        assertEquals(1000, HttpJson.parseRetryAfterMs("  1 "), "surrounding whitespace is tolerated");
    }

    @Test
    void absentOrMalformedHeaderYieldsNegative() {
        assertTrue(HttpJson.parseRetryAfterMs(null) < 0, "no header");
        assertTrue(HttpJson.parseRetryAfterMs("") < 0, "empty header");
        assertTrue(HttpJson.parseRetryAfterMs("   ") < 0, "blank header");
        assertTrue(HttpJson.parseRetryAfterMs("soon") < 0, "non-numeric, non-date");
        assertTrue(HttpJson.parseRetryAfterMs("-5") < 0, "negative delta-seconds is rejected");
    }

    @Test
    void parsesHttpDateForm() {
        String future = ZonedDateTime.now().plusSeconds(30).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        long ms = HttpJson.parseRetryAfterMs(future);
        assertTrue(ms > 20_000 && ms <= 30_000, "future HTTP-date resolves to its delta, got " + ms);
    }

    @Test
    void pastHttpDateYieldsNegative() {
        String past = ZonedDateTime.now().minusSeconds(30).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertTrue(HttpJson.parseRetryAfterMs(past) < 0, "a past date is not a usable back-off");
    }

    // ---- Retry behavior ----

    @Test
    void retriesRateLimitThenSucceeds_honoringRetryAfter() {
        FakeClient client = new FakeClient()
                .add(() -> resp(429, "", Map.of("Retry-After", List.of("1"))))
                .add(() -> resp(200, "{\"ok\":true}", Map.of()));
        List<Long> slept = new ArrayList<>();
        HttpJson http = new HttpJson("test", client, new ObjectMapper(), RetryPolicy.DEFAULT, slept::add);

        JsonNode n = http.get(URI_OK, "auth");

        assertTrue(n.path("ok").asBoolean(), "second attempt's body is returned");
        assertEquals(2, client.sends, "one 429, then the retry");
        assertEquals(List.of(1000L), slept, "backed off for exactly the Retry-After hint (1s)");
    }

    @Test
    void exhaustsRetriesOnPersistentRateLimit_thenThrowsRateLimited() {
        RetryPolicy fast = new RetryPolicy(3, 5, 5);
        FakeClient client = new FakeClient()
                .add(() -> resp(429, "", Map.of()))
                .add(() -> resp(429, "", Map.of()))
                .add(() -> resp(429, "", Map.of()));
        List<Long> slept = new ArrayList<>();
        HttpJson http = new HttpJson("test", client, new ObjectMapper(), fast, slept::add);

        assertThrows(UpstreamRateLimitedException.class, () -> http.get(URI_OK, "auth"));
        assertEquals(3, client.sends, "tried the full attempt budget");
        assertEquals(2, slept.size(), "slept between the 3 attempts");
    }

    @Test
    void retriesServerErrorThenSucceeds() {
        FakeClient client =
                new FakeClient().add(() -> resp(503, "", Map.of())).add(() -> resp(200, "{\"ok\":true}", Map.of()));
        HttpJson http = new HttpJson("test", client, new ObjectMapper(), RetryPolicy.DEFAULT, ms -> {});

        JsonNode n = http.get(URI_OK, "auth");
        assertTrue(n.path("ok").asBoolean());
        assertEquals(2, client.sends, "5xx is transient and retried");
    }

    @Test
    void retriesNetworkErrorThenSucceeds() {
        FakeClient client = new FakeClient()
                .add(() -> {
                    throw new IOException("connection reset");
                })
                .add(() -> resp(200, "{\"ok\":true}", Map.of()));
        HttpJson http = new HttpJson("test", client, new ObjectMapper(), RetryPolicy.DEFAULT, ms -> {});

        JsonNode n = http.get(URI_OK, "auth");
        assertTrue(n.path("ok").asBoolean());
        assertEquals(2, client.sends, "a network blip is retried");
    }

    @Test
    void doesNotRetryAuthFailure() {
        FakeClient client = new FakeClient().add(() -> resp(401, "", Map.of()));
        List<Long> slept = new ArrayList<>();
        HttpJson http = new HttpJson("test", client, new ObjectMapper(), RetryPolicy.DEFAULT, slept::add);

        TessaryException e = assertThrows(TessaryException.class, () -> http.get(URI_OK, "auth"));
        assertEquals(IngestError.UPSTREAM_AUTH_FAILED, e.error());
        assertEquals(1, client.sends, "auth failure is terminal — no retry");
        assertTrue(slept.isEmpty(), "no back-off for a non-retryable failure");
    }

    @Test
    void doesNotRetryClientError() {
        FakeClient client = new FakeClient().add(() -> resp(404, "", Map.of()));
        HttpJson http = new HttpJson("test", client, new ObjectMapper(), RetryPolicy.DEFAULT, ms -> {});

        TessaryException e = assertThrows(TessaryException.class, () -> http.get(URI_OK, "auth"));
        assertEquals(IngestError.UPSTREAM_FAILED, e.error());
        assertEquals(1, client.sends, "a 4xx won't change on retry");
    }

    @Test
    void interactivePolicyFailsFastUnderRateLimit() {
        FakeClient client = new FakeClient()
                // Upstream asks for a long wait; the interactive ceiling must override it.
                .add(() -> resp(429, "", Map.of("Retry-After", List.of("60"))))
                .add(() -> resp(429, "", Map.of("Retry-After", List.of("60"))));
        List<Long> slept = new ArrayList<>();
        HttpJson http = new HttpJson("test", client, new ObjectMapper(), RetryPolicy.INTERACTIVE, slept::add);

        assertThrows(UpstreamRateLimitedException.class, () -> http.get(URI_OK, "auth"));
        assertEquals(2, client.sends, "interactive policy allows one quick retry");
        assertEquals(List.of(2000L), slept, "a 60s Retry-After is capped to the 2s interactive ceiling");
    }

    // ---- fakes ----

    private static HttpResponse<String> resp(int status, String body, Map<String, List<String>> headers) {
        return new FakeResponse(status, body, HttpHeaders.of(headers, (k, v) -> true));
    }

    /** A queued HTTP outcome: a response, or a thrown IOException for the network-error case. */
    @FunctionalInterface
    private interface Outcome {
        HttpResponse<String> get() throws IOException;
    }

    /** Minimal HttpClient that replays queued outcomes; only {@code send} is exercised. */
    private static final class FakeClient extends HttpClient {
        private final Deque<Outcome> queue = new ArrayDeque<>();
        int sends = 0;

        FakeClient add(Outcome o) {
            queue.add(o);
            return this;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            sends++;
            Outcome o = queue.poll();
            if (o == null) throw new AssertionError("no more responses queued");
            @SuppressWarnings("unchecked")
            HttpResponse<T> typed = (HttpResponse<T>) o.get();
            return typed;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.net.CookieHandler> cookieHandler() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Redirect followRedirects() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.net.ProxySelector> proxy() {
            throw new UnsupportedOperationException();
        }

        @Override
        public javax.net.ssl.SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public javax.net.ssl.SSLParameters sslParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.net.Authenticator> authenticator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Version version() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<java.util.concurrent.Executor> executor() {
            throw new UnsupportedOperationException();
        }
    }

    /** Minimal HttpResponse&lt;String&gt;; only status/headers/body are read by HttpJson. */
    private static final class FakeResponse implements HttpResponse<String> {
        private final int status;
        private final String body;
        private final HttpHeaders headers;

        FakeResponse(int status, String body, HttpHeaders headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public HttpRequest request() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpClient.Version version() {
            throw new UnsupportedOperationException();
        }
    }
}
