// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest;

import ai.tessary.evals.model.ContentBlock;
import ai.tessary.evals.open.media.MediaStore;
import ai.tessary.evals.open.media.MediaStore.MediaRef;
import ai.tessary.evals.open.media.PdfTextExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Externalizes inline base64 image and document content out of the trace substrate into the
 * {@link MediaStore} at ingest. Runs at the persistence chokepoint ({@code SpanBatchWriter}) so EVERY
 * ingest path (Langfuse/Braintrust/Phoenix pull, JSONL upload, OTLP push) is covered by one seam — the
 * persisted {@code span_payload.input}/{@code .output} never carries base64. Each externalized image
 * becomes a {@link ContentBlock}-shaped {@code image_ref} node
 * ({@code {type:"image_ref", data:"<mediaId>", mediaType:"<mime>"}}); each externalized document
 * becomes a {@code document_ref} node whose {@code text} field ALSO carries the extracted PDF text (or
 * a labeled failure marker) so the judge boundary never needs a MediaStore round trip for the common
 * case (#985, Epic 8 Track B). Bytes live once (content-addressed, per-project deduped) in
 * {@code media_object}. Readers re-hydrate by id: the judge boundary via {@link MediaStore#get}
 * just-in-time, the frontend via the media serve endpoint.
 *
 * <p>Two entry points for the two content shapes the substrate persists:
 * <ul>
 *   <li>{@link #externalizeJson} — rewrites a raw OpenAI/Anthropic content <em>payload string</em>
 *       ({@code span_payload.input/output}, {@code tool_call} args/result) in place, replacing inline
 *       base64 image/document nodes with {@code image_ref}/{@code document_ref} nodes. Non-JSON or
 *       media-free payloads are returned byte-identical.</li>
 *   <li>{@link #externalizeBlocks} — replaces every {@code image_b64}/{@code document_b64} (and inline
 *       {@code data:} URI {@code image_url}) {@link ContentBlock} with its {@code _ref} counterpart,
 *       for a caller that already holds typed blocks rather than a payload string.</li>
 * </ul>
 *
 * <p><b>Every call reports the media it minted.</b> The ids are the only thing that can tie a
 * {@code media_object} row back to the span that carries it: the reference itself is a string inside the
 * payload JSON, invisible to FKs and cascades, so the caller writes a {@code media_ref} row per id in the
 * same transaction as the payload. Dropping them on the floor is what made every one of those rows
 * unreachable and uncollectable (#761), and a store that succeeds without the caller learning the id is
 * the bug re-appearing.
 *
 * <p>Recognises the same base64 shapes {@code ContentExtractor} does: Anthropic
 * {@code {type:"image"|"document", source:{type:"base64", data, media_type}}} and OpenAI
 * {@code {type:"image_url"|"input_image"|"output_image", image_url:{url:"data:…;base64,…"}}} /
 * {@code {type:"input_file"|"file", file_data:"data:…;base64,…"}}. An undecodable/oversized/otherwise-
 * failing image is left inline rather than dropped (never fatal to the batch) — the same
 * first-do-no-harm posture as the rest of the ingest path. A document whose PDF text extraction fails
 * (encrypted, scanned, corrupt) still externalizes — the bytes are preserved and {@code text} carries a
 * labeled failure marker rather than the batch failing or the bytes being dropped.
 */
@Component
public class MediaExternalizer {

    private static final Logger log = LoggerFactory.getLogger(MediaExternalizer.class);

    private static final String DATA_URI_PREFIX = "data:";

    /** The "nothing decodable" sentinel {@link #decodeBase64} returns instead of null. */
    private static final byte[] NO_BYTES = new byte[0];

    /** Alias for {@link PdfTextExtractor#DOCUMENT_TEXT_UNAVAILABLE_MARKER} — kept as a local name so
     *  call sites in this class read naturally; the literal itself lives in {@code core} so the judge
     *  boundary ({@code ContentBlocks}) can check against the exact same constant instead of only
     *  testing blank/null. */
    static final String DOCUMENT_TEXT_UNAVAILABLE_MARKER = PdfTextExtractor.DOCUMENT_TEXT_UNAVAILABLE_MARKER;

    private final MediaStore media;
    private final ObjectMapper mapper;

    public MediaExternalizer(MediaStore media, ObjectMapper mapper) {
        this.media = media;
        this.mapper = mapper;
    }

    /**
     * A rewritten payload and the media ids it now references.
     *
     * @param payload the payload string, byte-identical to the input when nothing was externalized.
     * @param mediaIds every {@code media_object} id this payload's {@code image_ref}/{@code document_ref}
     *     nodes point at, deduplicated and in first-seen order. Empty for the common media-free case.
     */
    public record Externalized(@Nullable String payload, List<String> mediaIds) {

        static Externalized unchanged(@Nullable String payload) {
            return new Externalized(payload, List.of());
        }
    }

    /**
     * Rewrite inline base64 image/document nodes in an OpenAI/Anthropic content payload to
     * {@code image_ref}/{@code document_ref} nodes, storing the decoded bytes in the {@link MediaStore}.
     * The payload comes back unchanged when it is null/blank, not JSON, or carries no inline base64
     * media (the common, media-free case pays only a parse), and the ids of every object it did
     * externalize come back with it.
     */
    public Externalized externalizeJson(String projectId, @Nullable String payload) {
        if (payload == null || payload.isBlank()) return Externalized.unchanged(payload);
        String trimmed = payload.stripLeading();
        if (trimmed.isEmpty() || (trimmed.charAt(0) != '[' && trimmed.charAt(0) != '{')) {
            return Externalized.unchanged(payload); // not a JSON container — no image node to externalize
        }
        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (Exception e) {
            return Externalized.unchanged(payload); // unparseable — leave verbatim, never fail the batch
        }
        Minted minted = new Minted();
        JsonNode rewritten = rewrite(projectId, root, minted);
        // No base64 media → identical bytes, avoid a needless re-serialize.
        if (minted.ids.isEmpty()) return Externalized.unchanged(payload);
        try {
            return new Externalized(mapper.writeValueAsString(rewritten), List.copyOf(minted.ids));
        } catch (Exception e) {
            // The rewrite cannot be persisted, so neither can its refs: reporting ids for a payload that
            // still carries the base64 would file media_ref rows for a reference the payload does not have.
            return Externalized.unchanged(payload);
        }
    }

    /**
     * Replace every base64 image/document block (and inline {@code data:} URI {@code image_url} block)
     * in {@code blocks} with its {@code _ref} counterpart. Text/URL/already-ref blocks pass through
     * unchanged. A block whose bytes cannot be stored is kept as-is (never dropped).
     */
    public ExternalizedBlocks externalizeBlocks(String projectId, List<ContentBlock> blocks) {
        List<ContentBlock> out = new ArrayList<>(blocks.size());
        Minted minted = new Minted();
        for (ContentBlock b : blocks) {
            out.add(externalizeBlock(projectId, b, minted));
        }
        return new ExternalizedBlocks(out, List.copyOf(minted.ids));
    }

    /** {@link Externalized}, for the typed-block entry point. */
    public record ExternalizedBlocks(List<ContentBlock> blocks, List<String> mediaIds) {}

    /** The ids minted while rewriting one payload, deduplicated in first-seen order. */
    private static final class Minted {
        private final Set<String> ids = new LinkedHashSet<>();

        void add(MediaRef ref) {
            ids.add(ref.id());
        }
    }

    private ContentBlock externalizeBlock(String projectId, ContentBlock b, Minted minted) {
        if (b == null || b.type() == null) return b;
        return switch (b.type()) {
            case ContentBlock.TYPE_IMAGE_B64 -> {
                MediaRef ref = putBase64(projectId, b.data(), b.mediaType(), "image/png");
                if (ref == null) yield b;
                minted.add(ref);
                yield ContentBlock.imageRef(ref.id(), b.mediaType());
            }
            case ContentBlock.TYPE_IMAGE_URL -> {
                DataUri du = parseDataUri(b.url());
                if (du == null) yield b; // a real http(s) URL stays a URL — nothing to externalize
                MediaRef ref = putBytes(projectId, du.bytes(), du.mime());
                if (ref == null) yield b;
                minted.add(ref);
                yield ContentBlock.imageRef(ref.id(), du.mime());
            }
            case ContentBlock.TYPE_DOCUMENT_B64 -> {
                byte[] bytes = decodeBase64(b.data());
                if (bytes.length == 0) yield b;
                MediaRef ref = putBytes(projectId, bytes, b.mediaType());
                if (ref == null) yield b;
                minted.add(ref);
                yield documentRefWithExtractedText(ref.id(), b.mediaType(), bytes);
            }
            // document_url (a real http(s) URL, or a data: URI carried in `url`): only the data: URI
            // form is inlined bytes worth externalizing; a real URL stays a URL — never fetched, same
            // posture as image_url.
            case ContentBlock.TYPE_DOCUMENT_URL -> {
                DataUri du = parseDataUri(b.url(), "application/pdf");
                if (du == null) yield b;
                MediaRef ref = putBytes(projectId, du.bytes(), du.mime());
                if (ref == null) yield b;
                minted.add(ref);
                yield documentRefWithExtractedText(ref.id(), du.mime(), du.bytes());
            }
            default -> b;
        };
    }

    // --- JSON tree rewrite ----------------------------------------------------

    private JsonNode rewrite(String projectId, JsonNode node, Minted minted) {
        if (node.isArray()) {
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode child : node) out.add(rewrite(projectId, child, minted));
            return out;
        }
        if (node.isObject()) {
            JsonNode ref = tryExternalizeMediaNode(projectId, node, minted);
            if (ref != null) return ref;
            ObjectNode out = mapper.createObjectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                out.set(field.getKey(), rewrite(projectId, field.getValue(), minted));
            }
            return out;
        }
        return node;
    }

    /**
     * If {@code node} is a base64 image or document content part, store the bytes and return the
     * replacement {@code _ref} node (recording the id); else null (the caller recurses into its fields).
     */
    private @Nullable JsonNode tryExternalizeMediaNode(String projectId, JsonNode node, Minted minted) {
        String type = node.path("type").asText("");
        // Anthropic: {type:"image", source:{type:"base64", data, media_type}}
        if ("image".equals(type)) {
            JsonNode src = node.get("source");
            if (src != null
                    && src.isObject()
                    && "base64".equals(src.path("type").asText(""))) {
                String data = src.path("data").asText("");
                String mime = src.path("media_type").asText("image/png");
                MediaRef ref = putBase64(projectId, data, mime, "image/png");
                if (ref != null) {
                    minted.add(ref);
                    return refNode(ContentBlock.imageRef(ref.id(), mime));
                }
            }
            return null;
        }
        // Anthropic: {type:"document", source:{type:"base64", data, media_type}}. The url-source variant
        // is left untouched — nothing to externalize, same as a real http(s) image_url.
        if ("document".equals(type)) {
            JsonNode src = node.get("source");
            if (src != null
                    && src.isObject()
                    && "base64".equals(src.path("type").asText(""))) {
                String data = src.path("data").asText("");
                String mime = src.path("media_type").asText("application/pdf");
                byte[] bytes = decodeBase64(data);
                if (bytes.length > 0) {
                    MediaRef ref = putBytes(projectId, bytes, mime);
                    if (ref != null) {
                        minted.add(ref);
                        return refNode(documentRefWithExtractedText(ref.id(), mime, bytes));
                    }
                }
            }
            return null;
        }
        // OpenAI: {type:"image_url"|"input_image"|"output_image", image_url:{url:"data:…"}} (or a bare
        // string url). Only inline data: URIs are externalized; a real http(s) URL is left untouched.
        if ("image_url".equals(type) || "input_image".equals(type) || "output_image".equals(type)) {
            String url = imageUrlOf(node);
            DataUri du = parseDataUri(url);
            if (du != null) {
                MediaRef ref = putBytes(projectId, du.bytes(), du.mime());
                if (ref != null) {
                    minted.add(ref);
                    return refNode(ContentBlock.imageRef(ref.id(), du.mime()));
                }
            }
            return null;
        }
        // OpenAI: {type:"input_file"|"file", file_data:"data:<mime>;base64,…"} — the document
        // counterpart of image_url's data-URI path. A bare url/file_url field is a real URL, left
        // untouched (nothing to externalize).
        if ("input_file".equals(type) || "file".equals(type)) {
            DataUri du = parseDataUri(node.path("file_data").asText(""), "application/pdf");
            if (du != null) {
                MediaRef ref = putBytes(projectId, du.bytes(), du.mime());
                if (ref != null) {
                    minted.add(ref);
                    return refNode(documentRefWithExtractedText(ref.id(), du.mime(), du.bytes()));
                }
            }
            return null;
        }
        return null;
    }

    /** The image URL of an OpenAI-style part: {@code image_url} as an object with a {@code url}, as a
     *  bare string, or a top-level {@code url} field. Empty when none present. */
    private static String imageUrlOf(JsonNode node) {
        JsonNode iu = node.get("image_url");
        if (iu != null) {
            if (iu.isObject()) return iu.path("url").asText("");
            if (iu.isTextual()) return iu.asText("");
        }
        return node.path("url").asText("");
    }

    /**
     * A {@code document_ref} block with {@code text} populated from {@link PdfTextExtractor}, run
     * ONCE here, or {@link #DOCUMENT_TEXT_UNAVAILABLE_MARKER} on any extraction failure (encrypted,
     * scanned, corrupt) — the bytes are preserved either way (already stored by the caller before this
     * method runs); only the label differs. This is what lets the judge boundary read {@code text}
     * directly for a {@code document_ref} block without a MediaStore round trip in the common case.
     */
    private static ContentBlock documentRefWithExtractedText(String mediaId, String mediaType, byte[] bytes) {
        Optional<String> text = PdfTextExtractor.extract(bytes);
        return new ContentBlock(
                ContentBlock.TYPE_DOCUMENT_REF,
                text.orElse(DOCUMENT_TEXT_UNAVAILABLE_MARKER),
                null,
                mediaId,
                mediaType);
    }

    private JsonNode refNode(ContentBlock ref) {
        // Serialize through ContentBlock so the on-wire ref node shape ({type,text,data,mediaType}) is
        // the single source of truth shared with ContentExtractor and the frontend.
        return mapper.valueToTree(ref);
    }

    // --- MediaStore puts ------------------------------------------------------

    private @Nullable MediaRef putBase64(
            String projectId, @Nullable String base64, @Nullable String mediaType, String defaultMediaType) {
        byte[] bytes = decodeBase64(base64);
        if (bytes.length == 0) return null;
        return putBytes(projectId, bytes, mediaType == null || mediaType.isBlank() ? defaultMediaType : mediaType);
    }

    /** Nothing decodable is a zero-length array, never null (SpotBugs PZLA): every caller's next move
     *  on "no bytes" is to leave the block inline, and {@link #putBytes} already rejects an empty array,
     *  so a sentinel array costs nothing and keeps a null out of a byte[] return. */
    private byte[] decodeBase64(@Nullable String base64) {
        if (base64 == null || base64.isBlank()) return NO_BYTES;
        try {
            return Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            log.info("skipping media externalization: undecodable base64 ({} chars) — left inline", base64.length());
            return NO_BYTES;
        }
    }

    private @Nullable MediaRef putBytes(String projectId, byte[] bytes, @Nullable String mediaType) {
        if (bytes.length == 0) return null;
        try {
            return media.put(projectId, bytes, mediaType == null || mediaType.isBlank() ? "image/png" : mediaType);
        } catch (RuntimeException e) {
            log.warn(
                    "media externalization store failed for project={} ({} bytes) — left inline",
                    projectId,
                    bytes.length,
                    e);
            return null;
        }
    }

    /** A parsed {@code data:<mime>;base64,<bytes>} URI. */
    private record DataUri(String mime, byte[] bytes) {}

    /**
     * Parse a {@code data:<mime>;base64,<payload>} URI into its declared mime + decoded bytes, or null
     * when {@code url} is not a base64 data URI (a real http(s) URL, a non-base64 data URI, or garbage).
     * {@code defaultMime} is applied when the header carries no mime segment — {@code "image/png"} for
     * an image_url data URI, {@code "application/pdf"} for a document data URI.
     */
    private static @Nullable DataUri parseDataUri(@Nullable String url) {
        return parseDataUri(url, "image/png");
    }

    private static @Nullable DataUri parseDataUri(@Nullable String url, String defaultMime) {
        if (url == null || !url.regionMatches(true, 0, DATA_URI_PREFIX, 0, DATA_URI_PREFIX.length())) {
            return null;
        }
        int comma = url.indexOf(',');
        if (comma < 0) return null;
        String header = url.substring(DATA_URI_PREFIX.length(), comma); // e.g. "image/png;base64"
        if (!header.toLowerCase(java.util.Locale.ROOT).contains(";base64")) return null;
        String mime = header.substring(0, header.indexOf(';'));
        if (mime.isBlank()) mime = defaultMime;
        try {
            byte[] bytes = Base64.getDecoder().decode(url.substring(comma + 1).strip());
            return new DataUri(mime, bytes);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
