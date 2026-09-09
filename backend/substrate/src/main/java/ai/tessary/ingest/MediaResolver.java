// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import ai.tessary.config.IngestProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Resolves out-of-band provider media into INLINE image content on the ingest path,
 * BEFORE a {@link RawEntry} is built — so the already-image-capable downstream (ContentExtractor →
 * ContentBlock → judge → frontend) is unchanged. Always runs — there is no on/off toggle. A payload
 * with no media reference is a cheap no-op (the rewrite early-returns before any HTTP call).
 *
 * <p><b>Live-data caveat (unchanged by always-on):</b> the resolver issues extra credentialed
 * media-download calls and is still not verified end-to-end against real provider data — watch the
 * first live import that carries media.
 *
 * <p>Stores resolved bytes inline as a {@code data:} URI in the existing TEXT column at this boundary
 * (consistent with the inline-storage decision) for images and PDF documents (#985, Epic 8 Track B,
 * Decision 2). Large-binary externalization OUT of that TEXT column and into the {@code MediaStore}
 * happens downstream, at the persistence chokepoint — see {@code MediaExternalizer}, which is fully
 * implemented (this resolver's job stops at inlining; it never touches {@code MediaStore} directly).
 *
 * <h2>Provider shapes (characterized from the adapter code + official docs)</h2>
 * <ul>
 *   <li><b>Langfuse</b>: media is referenced inline in input/output as the token
 *       {@code @@@langfuseMedia:type=<mime>|id=<mediaId>|source=<base64_data_uri|bytes|file>@@@}.
 *       Resolved via {@code GET {baseUrl}/api/public/media/{mediaId}} (HTTP Basic, same auth as the
 *       observations API), whose JSON carries a presigned {@code url}; we download that and inline it.
 *       This path is fully implemented.</li>
 *   <li><b>Braintrust</b>: attachments appear as a JSON object
 *       {@code {"type":"braintrust_attachment","key":...,"filename":...,"content_type":...}} (or
 *       {@code "external_attachment"} carrying an {@code s3://} url). The public REST download endpoint
 *       for a {@code braintrust_attachment} key is NOT documented (the SDK resolves it internally via
 *       ReadonlyAttachment), and {@code external_attachment}'s {@code s3://} url is not http(s) so
 *       {@code UrlGuard} rejects it. We therefore DETECT the reference and rewrite it to a labeled
 *       placeholder (never a silent drop) and flag it — see {@link #resolveBraintrustAttachment}.
 *       <b>LIVE-DATA CAVEAT:</b> the Braintrust download path needs a documented endpoint + real
 *       attachment data to finish; until then it is detect-and-label only.</li>
 * </ul>
 *
 * <p>SSRF + size safety (mustFix): every download URL is re-validated through
 * {@link UrlGuard#requirePublicHttp} and the body is capped by {@link BoundedBody} at
 * {@link IngestProperties#getMaxMediaBytes()} (distinct from the 32 MB JSON cap). Never throws on a
 * single media failure — a broken/oversized/blocked object is logged and the token is left intact (or
 * labeled), so one bad image never fails the whole import.
 */
@Component
public class MediaResolver {

    private static final Logger log = LoggerFactory.getLogger(MediaResolver.class);

    /** {@code @@@langfuseMedia:type=<mime>|id=<id>|source=<source>@@@} — fields can arrive in any order. */
    private static final Pattern LANGFUSE_MEDIA_TOKEN = Pattern.compile("@@@langfuseMedia:([^@]+)@@@");

    private final IngestProperties props;
    private final ObjectMapper mapper;
    private final HttpClient client;

    // CORRECTED 2026-09-02 (#985): this comment used to say the MediaStore SPI was deferred and
    // unbuilt ("build NOTHING yet ... No no-op interface now") — that was true when it was written and
    // is false today. `MediaStore` (backend/shared/.../open/media/MediaStore.java), its shipping
    // Postgres `bytea` implementation (`PostgresMediaStore`), and the externalization seam
    // (`MediaExternalizer`, running at the persistence chokepoint) all exist and are wired for both
    // images and PDF documents. This resolver's job stops one step short of that seam: it inlines
    // out-of-band provider media (Langfuse tokens today) as a `data:` URI in the TEXT column, and
    // `MediaExternalizer` is what externalizes inline base64 OUT of that column into `MediaStore`
    // afterward, at the batch-write chokepoint downstream of this class. The `media_ref` table
    // (0001) records which payload references which media_object, which is what makes the bytes
    // collectable — nothing about that changed.

    // @Autowired disambiguates the injection constructor from the package-private test ctor below
    // (two constructors otherwise leave Spring looking for a no-arg one). The mapper is the
    // shared strict @Primary bean (JacksonConfig) — same semantics as a bare new ObjectMapper().
    @Autowired
    public MediaResolver(IngestProperties props, ObjectMapper mapper) {
        this(
                props,
                mapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    // Package-private seam for tests: inject a stub HttpClient. Mirrors HttpJson's test ctor.
    MediaResolver(IngestProperties props, ObjectMapper mapper, HttpClient client) {
        this.props = props;
        this.mapper = mapper;
        this.client = client;
    }

    /** Max media objects a caller may resolve for one observation page (mustFix: page-batched, bounded). */
    public int maxFetchesPerPage() {
        return props.getMaxMediaFetchesPerPage();
    }

    /**
     * Rewrite any Langfuse media tokens in {@code payload} into inline {@code data:} URIs, charging
     * each resolved object against {@code remainingBudget} (the per-page fetch budget the caller
     * threads through so a dense page can't fan out unbounded). Returns {@code payload} unchanged when
     * there are no tokens or the budget is exhausted. {@code remainingBudget[0]} is decremented in
     * place for each fetch attempt.
     *
     * @param baseUrl    the provider base URL (already trailing-slash-stripped by the adapter)
     * @param authHeader the credentialed auth header the adapter uses for the observations API
     */
    public @Nullable String rewriteLangfuseMedia(
            @Nullable String payload, String baseUrl, String authHeader, int[] remainingBudget) {
        if (payload == null || payload.isEmpty() || !payload.contains("@@@langfuseMedia:")) return payload;
        Matcher m = LANGFUSE_MEDIA_TOKEN.matcher(payload);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement = m.group(0); // default: leave the token intact
            if (remainingBudget[0] > 0) {
                remainingBudget[0]--;
                String mediaId = field(m.group(1), "id");
                String mime = field(m.group(1), "type");
                String dataUri = resolveLangfuseMedia(baseUrl, authHeader, mediaId, mime);
                if (dataUri != null) replacement = dataUri;
            } else {
                log.info("media fetch budget exhausted on this page — leaving langfuseMedia token unresolved");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Resolve one Langfuse media id to an inline {@code data:<mime>;base64,<bytes>} URI, or null on any
     * failure (the caller leaves the token intact). Two credentialed calls, both guarded:
     * {@code GET /api/public/media/{id}} for the presigned download url, then the download itself —
     * each URL through {@link UrlGuard} + {@link BoundedBody}. Never throws.
     */
    private @Nullable String resolveLangfuseMedia(
            String baseUrl, String authHeader, @Nullable String mediaId, @Nullable String mimeHint) {
        if (mediaId == null || mediaId.isBlank()) return null;
        try {
            URI metaUri = UrlGuard.requirePublicHttp(baseUrl + "/api/public/media/" + mediaId);
            HttpResponse<String> metaRes = client.send(
                    HttpRequest.newBuilder(metaUri)
                            .GET()
                            .header("Accept", "application/json")
                            .header("Authorization", authHeader)
                            .timeout(Duration.ofSeconds(30))
                            .build(),
                    BoundedBody.string(props.getMaxMediaBytes()));
            if (metaRes.statusCode() / 100 != 2) {
                log.info(
                        "langfuse media meta lookup non-2xx ({}) for id={} — leaving token",
                        metaRes.statusCode(),
                        mediaId);
                return null;
            }
            JsonNode meta = mapper.readTree(metaRes.body());
            String url = meta.path("url").asText(null);
            String contentType = meta.path("contentType").asText(mimeHint == null ? "image/png" : mimeHint);
            if (url == null || url.isBlank()) return null;
            // Only inline images and PDF documents (#985, Decision 2 — no audio/video). Anything else
            // is left as a token rather than inlined — it would only reach the judge as an unsupported
            // block.
            if (contentType == null || !(contentType.startsWith("image/") || "application/pdf".equals(contentType))) {
                log.info(
                        "langfuse media id={} is neither image nor application/pdf ({}); not inlining",
                        mediaId,
                        contentType);
                return null;
            }
            URI dlUri = UrlGuard.requirePublicHttp(url);
            HttpResponse<byte[]> dl = client.send(
                    HttpRequest.newBuilder(dlUri)
                            .GET()
                            .timeout(Duration.ofSeconds(60))
                            .build(),
                    boundedBytes(props.getMaxMediaBytes()));
            if (dl.statusCode() / 100 != 2) {
                log.info("langfuse media download non-2xx ({}) for id={} — leaving token", dl.statusCode(), mediaId);
                return null;
            }
            String b64 = Base64.getEncoder().encodeToString(dl.body());
            log.debug("resolved langfuse media id={} ({} bytes, {})", mediaId, dl.body().length, contentType);
            return "data:" + contentType + ";base64," + b64;
        } catch (IOException | RuntimeException e) {
            // Includes UrlGuard's TessaryException (private/internal address), BoundedBody overflow, and
            // network errors. One bad object never fails the import — leave the token, log, move on.
            log.info("langfuse media resolve failed for id={}: {}", mediaId, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Detect a Braintrust attachment reference and rewrite it to a labeled placeholder. See the class
     * doc's LIVE-DATA CAVEAT: the public download endpoint for a {@code braintrust_attachment} key is
     * undocumented and {@code external_attachment}'s {@code s3://} url is non-http(s) (UrlGuard rejects
     * it), so v1 cannot fetch the bytes. We never silently drop: the reference becomes
     * {@code [attachment: <filename> (<content_type>)]} so the FACT of the image survives into the
     * grader's text view, and the caller can flag it.
     *
     * @return a labeled string when {@code node} is a recognised attachment reference, else null
     */
    public @Nullable String resolveBraintrustAttachment(@Nullable JsonNode node) {
        if (node == null || !node.isObject()) return null;
        String type = node.path("type").asText("");
        if (!"braintrust_attachment".equals(type) && !"external_attachment".equals(type)) return null;
        String filename = node.path("filename").asText("attachment");
        String contentType = node.path("content_type").asText("application/octet-stream");
        // TODO(MediaStore SPI, deferred): once a documented Braintrust attachment-download endpoint
        // exists, fetch the bytes here through UrlGuard + BoundedBody and inline an image data: URI
        // (image/* only), exactly like the Langfuse path. Large-binary externalization (audio/video/
        // large PDF) plugs into the deferred MediaStore SPI at this same ingest/resolver boundary —
        // it arrives with its first real object-store impl, not now.
        log.info(
                "braintrust attachment detected (filename={}, type={}) — download endpoint undocumented; "
                        + "labeling rather than inlining (live-data caveat)",
                filename,
                contentType);
        return "[attachment: " + filename + " (" + contentType + ")]";
    }

    /** Extract one {@code key=value} field from the token body ({@code type=...|id=...|source=...}). */
    private static @Nullable String field(@Nullable String body, String key) {
        if (body == null) return null;
        for (String part : body.split("\\|", -1)) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(key)) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /**
     * A byte[] body handler that aborts past {@code maxBytes}, the binary analogue of
     * {@link BoundedBody#string(long)}. Counts bytes as they arrive and, on overflow, cancels the
     * upstream and fails the body future — a hostile/oversized image (or one lying about its
     * Content-Length) never buffers more than the cap into the heap.
     */
    private static HttpResponse.BodyHandler<byte[]> boundedBytes(long maxBytes) {
        return info -> new BoundedByteSubscriber(maxBytes);
    }

    /** Delegates to the JDK byte-array subscriber, counting bytes and aborting past the cap. */
    private static final class BoundedByteSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final long maxBytes;
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final java.util.concurrent.atomic.AtomicLong seen = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        private java.util.concurrent.Flow.@Nullable Subscription subscription;

        BoundedByteSubscriber(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
            this.subscription = s;
            delegate.onSubscribe(s);
        }

        @Override
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            if (done.get()) return;
            long total = seen.addAndGet(
                    buffers.stream().mapToLong(java.nio.ByteBuffer::remaining).sum());
            if (total > maxBytes) {
                if (done.compareAndSet(false, true)) {
                    // onSubscribe always precedes onNext per the Flow contract, so subscription is set.
                    java.util.concurrent.Flow.Subscription s = subscription;
                    if (s != null) s.cancel();
                    delegate.onError(new IOException("media exceeded " + maxBytes + " bytes"));
                }
                return;
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable t) {
            if (done.compareAndSet(false, true)) delegate.onError(t);
        }

        @Override
        public void onComplete() {
            if (done.compareAndSet(false, true)) delegate.onComplete();
        }
    }
}
