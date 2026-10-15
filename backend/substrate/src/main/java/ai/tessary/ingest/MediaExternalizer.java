// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import ai.tessary.model.ContentBlock;
import ai.tessary.open.media.MediaStore;
import ai.tessary.open.media.MediaStore.MediaRef;
import ai.tessary.open.media.PdfTextExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Runs at the persistence chokepoint ({@code SpanBatchWriter}) so every ingest path is covered and stored payloads
 * never carry base64. Every call reports the media ids it minted: the ref is a string inside JSON, invisible to FKs, so
 * the caller writes a {@code media_ref} row per id or the object is uncollectable. Failures leave media inline rather
 * than failing the batch; a document whose text extraction fails still externalizes with a labeled marker.
 */
@Component
public class MediaExternalizer {

    private static final Logger log = LoggerFactory.getLogger(MediaExternalizer.class);

    private static final String DATA_URI_PREFIX = "data:";

    private static final byte[] NO_BYTES = new byte[0];

    /** The literal lives in {@code core} so the judge boundary checks the exact same constant. */
    static final String DOCUMENT_TEXT_UNAVAILABLE_MARKER = PdfTextExtractor.DOCUMENT_TEXT_UNAVAILABLE_MARKER;

    private final MediaStore media;
    private final ObjectMapper mapper;

    public MediaExternalizer(MediaStore media, ObjectMapper mapper) {
        this.media = media;
        this.mapper = mapper;
    }

    /** @param payload byte-identical to the input when nothing was externalized */
    public record Externalized(@Nullable String payload, List<String> mediaIds) {

        static Externalized unchanged(@Nullable String payload) {
            return new Externalized(payload, List.of());
        }
    }

    /** A media-free payload pays only a parse. */
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
        // No base64 media: identical bytes, avoid a needless re-serialize.
        if (minted.ids.isEmpty()) return Externalized.unchanged(payload);
        return new Externalized(rewritten.toString(), List.copyOf(minted.ids));
    }

    private static final class Minted {
        private final Set<String> ids = new LinkedHashSet<>();

        void add(MediaRef ref) {
            ids.add(ref.id());
        }
    }

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
        // The url-source variant has nothing to externalize.
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
        // Only inline data: URIs are externalized; a real http(s) URL is left untouched.
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
        // A bare url/file_url field is a real URL, left untouched.
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

    private static String imageUrlOf(JsonNode node) {
        JsonNode iu = node.get("image_url");
        if (iu != null) {
            if (iu.isObject()) return iu.path("url").asText("");
            if (iu.isTextual()) return iu.asText("");
        }
        return node.path("url").asText("");
    }

    /** Extraction runs once here, so the judge boundary reads {@code text} without a MediaStore round trip. */
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
        // Through ContentBlock, so the ref node shape is shared with ContentExtractor and the frontend.
        return mapper.valueToTree(ref);
    }

    private @Nullable MediaRef putBase64(
            String projectId, @Nullable String base64, @Nullable String mediaType, String defaultMediaType) {
        byte[] bytes = decodeBase64(base64);
        if (bytes.length == 0) return null;
        return putBytes(projectId, bytes, mediaType == null || mediaType.isBlank() ? defaultMediaType : mediaType);
    }

    /** Zero-length, never null (SpotBugs PZLA); {@link #putBytes} already rejects an empty array. */
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

    private record DataUri(String mime, byte[] bytes) {}

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
