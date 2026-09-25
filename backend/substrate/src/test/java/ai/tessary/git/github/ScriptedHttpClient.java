// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * A hand-written stand-in for the JDK {@link HttpClient}: answers each request by its exact URI from a
 * script, and records every request it was sent. The last scripted answer for a URI repeats; an unscripted
 * URI fails the test. An answer is a response, an {@link IOException} or {@link InterruptedException} to
 * throw, or a {@link Deferred} computed when the request arrives.
 */
final class ScriptedHttpClient extends HttpClient {

    /** An answer computed at request time, for a test that has to hold a request open. */
    @FunctionalInterface
    interface Deferred {
        Object answer() throws Exception;
    }

    private final Map<String, Deque<Object>> answers = new ConcurrentHashMap<>();
    private final List<HttpRequest> sent = new CopyOnWriteArrayList<>();

    ScriptedHttpClient on(String uri, Object... responses) {
        answers.computeIfAbsent(uri, k -> new ConcurrentLinkedDeque<>()).addAll(List.of(responses));
        return this;
    }

    List<HttpRequest> sent() {
        return sent;
    }

    static HttpResponse<String> response(int status, String body) {
        return new Response(status, body, HttpHeaders.of(Map.of(), (k, v) -> true));
    }

    /** A response carrying a {@code Link} header whose rel="next" is {@code next}. */
    static HttpResponse<String> response(int status, String body, String next) {
        return new Response(
                status, body, HttpHeaders.of(Map.of("Link", List.of("<" + next + ">; rel=\"next\"")), (k, v) -> true));
    }

    /** The body a request carried, read back off its publisher. */
    static String body(HttpRequest req) {
        List<ByteBuffer> chunks = new ArrayList<>();
        req.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                chunks.add(item);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {}
        });
        StringBuilder sb = new StringBuilder();
        for (ByteBuffer b : chunks) sb.append(StandardCharsets.UTF_8.decode(b));
        return sb.toString();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        sent.add(req);
        Deque<Object> script = answers.get(req.uri().toString());
        if (script == null || script.isEmpty()) {
            throw new AssertionError("no answer scripted for " + req.method() + " " + req.uri());
        }
        Object answer = script.size() > 1 ? script.poll() : script.peek();
        if (answer instanceof Deferred d) {
            try {
                answer = d.answer();
            } catch (IOException | InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        if (answer instanceof IOException e) throw e;
        if (answer instanceof InterruptedException e) throw e;
        @SuppressWarnings("unchecked")
        HttpResponse<T> res = (HttpResponse<T>) answer;
        return res;
    }

    private record Response(int statusCode, String body, HttpHeaders headers) implements HttpResponse<String> {
        @Override
        public HttpRequest request() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }

    // ---- the rest of HttpClient, which nothing under test touches ----------

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest req, HttpResponse.BodyHandler<T> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest req, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SSLParameters sslParameters() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }
}
