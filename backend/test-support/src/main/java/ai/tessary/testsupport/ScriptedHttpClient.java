// SPDX-License-Identifier: Apache-2.0
package ai.tessary.testsupport;

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
import org.jspecify.annotations.Nullable;

public final class ScriptedHttpClient extends HttpClient {

    @FunctionalInterface
    public interface Deferred {
        Object answer() throws Exception;
    }

    private final Map<String, Deque<Object>> answers = new ConcurrentHashMap<>();
    private final List<HttpRequest> sent = new CopyOnWriteArrayList<>();
    private final @Nullable Object anyUri;
    private volatile @Nullable String lastBody;

    public ScriptedHttpClient() {
        this(null);
    }

    private ScriptedHttpClient(@Nullable Object anyUri) {
        this.anyUri = anyUri;
    }

    public static ScriptedHttpClient answering(int status, String body) {
        return new ScriptedHttpClient(response(status, body));
    }

    public static ScriptedHttpClient failingWith(Exception failure) {
        return new ScriptedHttpClient(failure);
    }

    public ScriptedHttpClient on(String uri, Object... responses) {
        answers.computeIfAbsent(uri, k -> new ConcurrentLinkedDeque<>()).addAll(List.of(responses));
        return this;
    }

    public List<HttpRequest> sent() {
        return sent;
    }

    public @Nullable HttpRequest lastRequest() {
        return sent.isEmpty() ? null : sent.getLast();
    }

    public @Nullable String lastBody() {
        return lastBody;
    }

    public static HttpResponse<String> response(int status, String body) {
        return new Response(status, body, HttpHeaders.of(Map.of(), (k, v) -> true), null);
    }

    public static HttpResponse<String> response(int status, String body, String next) {
        return new Response(
                status,
                body,
                HttpHeaders.of(Map.of("Link", List.of("<" + next + ">; rel=\"next\"")), (k, v) -> true),
                null);
    }

    public static String body(HttpRequest req) {
        List<ByteBuffer> chunks = new ArrayList<>();
        req.bodyPublisher()
                .ifPresent(p -> p.subscribe(new Flow.Subscriber<ByteBuffer>() {
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
                }));
        StringBuilder sb = new StringBuilder();
        for (ByteBuffer b : chunks) sb.append(StandardCharsets.UTF_8.decode(b));
        return sb.toString();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        sent.add(req);
        lastBody = body(req);
        Deque<Object> script = answers.get(req.uri().toString());
        Object answer;
        if (script != null && !script.isEmpty()) {
            answer = script.size() > 1 ? script.poll() : script.peek();
        } else if (anyUri != null) {
            answer = anyUri;
        } else {
            throw new AssertionError("no answer scripted for " + req.method() + " " + req.uri());
        }
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
        if (!(answer instanceof Response r)) throw new AssertionError("not an answer: " + answer);
        @SuppressWarnings("unchecked")
        HttpResponse<T> res = (HttpResponse<T>) new Response(r.statusCode(), r.body(), r.headers(), req);
        return res;
    }

    private record Response(
            int statusCode,
            String body,
            HttpHeaders headers,
            @Nullable HttpRequest sentRequest) implements HttpResponse<String> {
        @Override
        public HttpRequest request() {
            if (sentRequest == null) throw new UnsupportedOperationException();
            return sentRequest;
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
            return request().uri();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }

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
