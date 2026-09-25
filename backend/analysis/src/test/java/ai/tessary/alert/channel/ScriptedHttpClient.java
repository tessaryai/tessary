// SPDX-License-Identifier: Apache-2.0
package ai.tessary.alert.channel;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.jspecify.annotations.Nullable;

/**
 * An {@link HttpClient} for the {@link ChannelHttp} test seam that answers every send with one scripted
 * outcome (a status and body, or a thrown exception) and keeps the last request it was handed, so a
 * connector test can assert both what was sent and what the connector made of the answer. No socket is
 * opened; {@code UrlGuard} still runs for real on the URL before the send reaches here.
 */
final class ScriptedHttpClient extends HttpClient {

    private final int status;
    private final String body;
    private final @Nullable Exception failure;

    @Nullable
    HttpRequest lastRequest;

    @Nullable
    String lastBody;

    private ScriptedHttpClient(int status, String body, @Nullable Exception failure) {
        this.status = status;
        this.body = body;
        this.failure = failure;
    }

    static ScriptedHttpClient answering(int status, String body) {
        return new ScriptedHttpClient(status, body, null);
    }

    static ScriptedHttpClient failingWith(Exception failure) {
        return new ScriptedHttpClient(0, "", failure);
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        lastRequest = request;
        lastBody = read(request);
        if (failure instanceof IOException io) throw io;
        if (failure instanceof InterruptedException ie) throw ie;
        @SuppressWarnings("unchecked")
        HttpResponse<T> typed = (HttpResponse<T>) new Response(request, status, body);
        return typed;
    }

    private static String read(HttpRequest request) {
        StringBuilder sb = new StringBuilder();
        request.bodyPublisher()
                .ifPresent(p -> p.subscribe(new Flow.Subscriber<ByteBuffer>() {
                    @Override
                    public void onSubscribe(Flow.Subscription s) {
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(ByteBuffer item) {
                        byte[] b = new byte[item.remaining()];
                        item.get(b);
                        sb.append(new String(b, StandardCharsets.UTF_8));
                    }

                    @Override
                    public void onError(Throwable t) {}

                    @Override
                    public void onComplete() {}
                }));
        return sb.toString();
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

    private record Response(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
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
        public java.net.URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
