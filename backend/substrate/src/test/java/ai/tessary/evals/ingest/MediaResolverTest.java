// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.evals.config.IngestProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MediaResolver}. Focuses on the project's "what to test" surface —
 * the SSRF/size guards, the per-page fetch budget, the token/attachment parsing, and the
 * never-silently-drop contract — using a minimal fake {@link HttpClient}. The live end-to-end
 * resolve against a real Langfuse/Braintrust media object is out of scope (no live provider
 * data here) and is the documented live-data caveat.
 */
class MediaResolverTest {

    private static final ObjectMapper M = new ObjectMapper();
    // TEST-NET-3 (203.0.113.0/24): a public literal that clears UrlGuard without a real DNS lookup.
    private static final String PUBLIC_BASE = "https://203.0.113.7";

    private static IngestProperties props(long maxBytes, int perPage) {
        IngestProperties p = new IngestProperties();
        p.setMaxMediaBytes(maxBytes);
        p.setMaxMediaFetchesPerPage(perPage);
        return p;
    }

    @Test
    void langfuse_token_resolvedToInlineDataUri() {
        byte[] png = {1, 2, 3, 4};
        FakeClient client = new FakeClient()
                // 1) GET /api/public/media/{id} -> presigned url + contentType
                .addString(
                        200, "{\"mediaId\":\"m1\",\"contentType\":\"image/png\",\"url\":\"" + PUBLIC_BASE + "/dl/m1\"}")
                // 2) download the presigned url -> raw bytes
                .addBytes(200, png);
        MediaResolver r = new MediaResolver(props(8L * 1024 * 1024, 10), M, client);

        String payload = "before @@@langfuseMedia:type=image/png|id=m1|source=bytes@@@ after";
        int[] budget = {10};
        String out = r.rewriteLangfuseMedia(payload, PUBLIC_BASE, "Basic xyz", budget);
        assertNotNull(out);

        String b64 = Base64.getEncoder().encodeToString(png);
        assertTrue(out.contains("data:image/png;base64," + b64), "token must be replaced by an inline data URI");
        assertFalse(out.contains("@@@langfuseMedia"), "the token must be gone");
        assertEquals(9, budget[0], "one media fetch consumes one budget unit");
    }

    @Test
    void langfuse_budgetExhausted_leavesTokenIntact_noFetch() {
        FakeClient client = new FakeClient(); // no responses queued; a fetch would AssertionError
        MediaResolver r = new MediaResolver(props(1024, 10), M, client);

        String payload = "@@@langfuseMedia:type=image/png|id=m1|source=bytes@@@";
        int[] budget = {0}; // already spent
        String out = r.rewriteLangfuseMedia(payload, PUBLIC_BASE, "Basic xyz", budget);
        assertNotNull(out);

        assertEquals(payload, out, "with no budget the token is left untouched");
        assertEquals(0, client.sends, "no HTTP call when the budget is spent");
    }

    @Test
    void langfuse_metaUrlPrivateAddress_rejectedBySsrfGuard_tokenKept() {
        // The presigned url points at an internal address — UrlGuard must reject it and the resolver
        // must swallow the failure and leave the token rather than fetching it.
        FakeClient client = new FakeClient()
                .addString(200, "{\"contentType\":\"image/png\",\"url\":\"http://169.254.169.254/latest/meta-data\"}");
        MediaResolver r = new MediaResolver(props(1024, 10), M, client);

        String payload = "@@@langfuseMedia:type=image/png|id=m1|source=bytes@@@";
        int[] budget = {10};
        String out = r.rewriteLangfuseMedia(payload, PUBLIC_BASE, "Basic xyz", budget);
        assertNotNull(out);

        assertTrue(out.contains("@@@langfuseMedia"), "an SSRF-blocked download leaves the token intact");
    }

    @Test
    void langfuse_nonImageNonPdfMedia_notInlined() {
        FakeClient client = new FakeClient()
                .addString(200, "{\"contentType\":\"audio/mpeg\",\"url\":\"" + PUBLIC_BASE + "/dl/a1\"}");
        MediaResolver r = new MediaResolver(props(1024, 10), M, client);

        String payload = "@@@langfuseMedia:type=audio/mpeg|id=a1|source=bytes@@@";
        int[] budget = {10};
        String out = r.rewriteLangfuseMedia(payload, PUBLIC_BASE, "Basic xyz", budget);
        assertNotNull(out);

        assertTrue(
                out.contains("@@@langfuseMedia"), "non-image, non-pdf media is not inlined (audio/video out of scope)");
        assertEquals(1, client.sends, "only the meta lookup happened; no download for unsupported media");
    }

    @Test
    void langfuse_pdfToken_resolvedToInlineDataUri() {
        // #985, Decision 2: the images-only gate widens to also accept application/pdf.
        byte[] pdf = {0x25, 0x50, 0x44, 0x46}; // "%PDF" magic bytes, contents don't matter to the resolver
        FakeClient client = new FakeClient()
                .addString(
                        200,
                        "{\"mediaId\":\"d1\",\"contentType\":\"application/pdf\",\"url\":\"" + PUBLIC_BASE
                                + "/dl/d1\"}")
                .addBytes(200, pdf);
        MediaResolver r = new MediaResolver(props(8L * 1024 * 1024, 10), M, client);

        String payload = "@@@langfuseMedia:type=application/pdf|id=d1|source=bytes@@@";
        int[] budget = {10};
        String out = r.rewriteLangfuseMedia(payload, PUBLIC_BASE, "Basic xyz", budget);
        assertNotNull(out);

        String b64 = Base64.getEncoder().encodeToString(pdf);
        assertTrue(out.contains("data:application/pdf;base64," + b64), "a PDF token must be inlined too");
        assertFalse(out.contains("@@@langfuseMedia"), "the token must be gone");
    }

    @Test
    void langfuse_noToken_returnsPayloadUnchanged_noFetch() {
        FakeClient client = new FakeClient();
        MediaResolver r = new MediaResolver(props(1024, 10), M, client);
        String payload = "[{\"role\":\"user\",\"content\":\"no media here\"}]";
        assertEquals(payload, r.rewriteLangfuseMedia(payload, PUBLIC_BASE, "Basic xyz", new int[] {10}));
        assertEquals(0, client.sends);
    }

    @Test
    void braintrust_attachment_labeledNotDropped() throws Exception {
        MediaResolver r = new MediaResolver(props(1024, 10), M, new FakeClient());
        var node = M.readTree(
                "{\"type\":\"braintrust_attachment\",\"key\":\"attachments/abc\",\"filename\":\"shot.jpg\",\"content_type\":\"image/jpeg\"}");
        String label = r.resolveBraintrustAttachment(node);
        assertEquals("[attachment: shot.jpg (image/jpeg)]", label);
    }

    @Test
    void braintrust_externalAttachment_alsoLabeled() throws Exception {
        MediaResolver r = new MediaResolver(props(1024, 10), M, new FakeClient());
        var node = M.readTree(
                "{\"type\":\"external_attachment\",\"url\":\"s3://b/doc.pdf\",\"filename\":\"doc.pdf\",\"content_type\":\"application/pdf\"}");
        assertEquals("[attachment: doc.pdf (application/pdf)]", r.resolveBraintrustAttachment(node));
    }

    @Test
    void braintrust_nonAttachmentObject_returnsNull() throws Exception {
        MediaResolver r = new MediaResolver(props(1024, 10), M, new FakeClient());
        assertNull(r.resolveBraintrustAttachment(M.readTree("{\"type\":\"text\",\"text\":\"hi\"}")));
        assertNull(r.resolveBraintrustAttachment(M.readTree("\"a bare string\"")));
    }

    // ---- fakes ----

    /** Minimal HttpClient that replays queued typed bodies (String for the meta GET, byte[] for the
     *  download), matching what MediaResolver requests. */
    private static final class FakeClient extends HttpClient {
        private final Deque<Object[]> queue = new ArrayDeque<>(); // [status, body]
        int sends = 0;

        FakeClient addString(int status, String body) {
            queue.add(new Object[] {status, body});
            return this;
        }

        FakeClient addBytes(int status, byte[] body) {
            queue.add(new Object[] {status, body});
            return this;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            sends++;
            Object[] o = queue.poll();
            if (o == null) throw new AssertionError("no more responses queued");
            @SuppressWarnings("unchecked")
            HttpResponse<T> typed = (HttpResponse<T>) new FakeResponse<>((int) o[0], o[1], request.uri());
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
        public SSLContext sslContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLParameters sslParameters() {
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

    /** Minimal HttpResponse&lt;T&gt;; only statusCode/body/uri are read by MediaResolver. */
    private static final class FakeResponse<T> implements HttpResponse<T> {
        private final int status;
        private final Object body;
        private final URI uri;

        FakeResponse(int status, Object body, URI uri) {
            this.status = status;
            this.body = body;
            this.uri = uri;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T body() {
            return (T) body;
        }

        @Override
        public HttpRequest request() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.<String, List<String>>of(), (k, v) -> true);
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return uri;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
