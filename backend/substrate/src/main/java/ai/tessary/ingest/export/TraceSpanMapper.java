// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.export;

import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.model.ContentBlock;
import ai.tessary.model.ContentExtractor;
import ai.tessary.open.media.MediaStore;
import ai.tessary.open.media.MediaStore.MediaRef;
import ai.tessary.open.media.MediaStore.StoredMedia;
import ai.tessary.open.media.PdfTextExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Emits the "messages-as-attributes" shape the evals plugin's Path A reads. Trace and span ids are written through
 * untouched, so the round trip is exact.
 */
public final class TraceSpanMapper {

    // A static bare mapper, not the JacksonConfig bean: this is a non-Spring utility, and the @Primary bean has
    // identical strict semantics.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TraceSpanMapper() {}

    /**
     * Pass {@code null} for {@code mediaStore} and {@code projectId} to keep ref blocks as their {@code omitted} label.
     */
    public static String toSpanLine(
            RawEntry raw, String serviceName, @Nullable MediaStore mediaStore, @Nullable String projectId) {
        return toSpan(raw, serviceName, mediaStore, projectId).toString();
    }

    /**
     * Pass {@code null} for both when no store is available; the block then keeps its {@code omitted} label, never a
     * silent collapse.
     */
    public static ObjectNode toSpan(
            RawEntry raw, String serviceName, @Nullable MediaStore mediaStore, @Nullable String projectId) {
        // Prefer a provider declared upstream over inferring from the model name, which would collapse an unrecognized
        // model to "other".
        String system = declaredSystem(raw.metadata());
        if (system == null) system = inferSystem(raw.model());

        ObjectNode span = MAPPER.createObjectNode();
        String rawName = raw.name();
        span.put("name", rawName != null && !rawName.isBlank() ? rawName : "chat " + system);
        span.put("kind", "SpanKind.CLIENT");

        ObjectNode context = span.putObject("context");
        context.put("trace_id", raw.traceId());
        context.put("span_id", raw.sourceExternalId());

        span.put("parent_id", raw.parentId()); // null serializes as JSON null
        span.put("start_time", raw.timestamp());
        span.put("end_time", raw.timestamp());
        span.putObject("status").put("status_code", "OK");

        ObjectNode attrs = span.putObject("attributes");
        attrs.put(GenAiAttributes.SYSTEM, system);
        // Honor the canonical operation kind rather than hard-coding "chat".
        attrs.put(GenAiAttributes.OPERATION_NAME, KindNormalizer.operationName(raw.operationKind()));
        if (raw.model() != null) attrs.put(GenAiAttributes.REQUEST_MODEL, raw.model());
        attrs.put(
                GenAiAttributes.INPUT_MESSAGES,
                writeArray(normalizeMessages(raw.input(), "user", false, mediaStore, projectId)));
        attrs.put(
                GenAiAttributes.OUTPUT_MESSAGES,
                writeArray(normalizeMessages(raw.output(), "assistant", true, mediaStore, projectId)));
        emitUsageAndTool(attrs, raw.metadata());

        span.putArray("events");
        ObjectNode resource = span.putObject("resource");
        resource.putObject("attributes")
                .put(
                        "service.name",
                        serviceName == null || serviceName.isBlank() ? "tessary-evals-export" : serviceName);
        return span;
    }

    /** When {@code addFinishReason}, each message carries {@code finish_reason:"end_turn"}. */
    static ArrayNode normalizeMessages(
            @Nullable String payload,
            String defaultRole,
            boolean addFinishReason,
            @Nullable MediaStore mediaStore,
            @Nullable String projectId) {
        ArrayNode out = MAPPER.createArrayNode();
        JsonNode root = tryParse(payload);

        if (root != null && root.isArray() && hasRole(root)) {
            for (JsonNode el : root) {
                if (!el.isObject()) continue;
                String role = el.path("role").asText(defaultRole);
                JsonNode contentNode = el.has("parts") ? el.get("parts") : el.get("content");
                out.add(message(role, contentNode, addFinishReason, mediaStore, projectId));
            }
            if (!out.isEmpty()) return out;
        }

        if (root != null) {
            out.add(message(defaultRole, root, addFinishReason, mediaStore, projectId));
        } else {
            out.add(textMessage(defaultRole, payload == null ? "" : payload, addFinishReason));
        }
        return out;
    }

    /**
     * Media is inlined as a base64 {@code data:} URI inside the plain-string content field the plugin's Path-A format
     * specifies, never a new part shape. {@code has_media:true} lets a reader find media without scanning every part.
     */
    private static ObjectNode message(
            String role,
            @Nullable JsonNode contentNode,
            boolean addFinishReason,
            @Nullable MediaStore mediaStore,
            @Nullable String projectId) {
        var blocks = ContentExtractor.blocksFromContent(contentNode);
        if (blocks.isEmpty()) {
            // Keep the JSON-flatten behaviour so non-message content still rides through as text.
            String text = ContentExtractor.flattenContentText(contentNode);
            return textMessage(role, text, addFinishReason);
        }
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", role == null || role.isBlank() ? "user" : role);
        ArrayNode parts = msg.putArray("parts");
        boolean hasMedia = false;
        StringBuilder textRun = new StringBuilder();
        for (var b : blocks) {
            if (b.isMedia()) {
                flushText(parts, textRun);
                parts.add(mediaPart(b, mediaStore, projectId));
                hasMedia = true;
            } else if (b.text() != null) {
                if (textRun.length() > 0) textRun.append('\n');
                textRun.append(b.text());
            }
        }
        flushText(parts, textRun);
        if (parts.isEmpty()) parts.addObject().put("type", "text").put("content", "");
        if (hasMedia) msg.put("has_media", true);
        if (addFinishReason) msg.put("finish_reason", "end_turn");
        return msg;
    }

    private static void flushText(ArrayNode parts, StringBuilder textRun) {
        if (textRun.length() == 0) return;
        parts.addObject().put("type", "text").put("content", textRun.toString());
        textRun.setLength(0);
    }

    /**
     * An http(s) URL is never fetched at export time. Without bytes a document falls back to its ingest-extracted text,
     * as the judge boundary does, so a media-store miss doesn't discard free PDF text; only then the content-free
     * label.
     */
    private static ObjectNode mediaPart(ContentBlock b, @Nullable MediaStore mediaStore, @Nullable String projectId) {
        String kind = b.isDocument() ? "document" : "image";
        String dataUri = inlineDataUri(b, mediaStore, projectId);
        String content;
        if (dataUri != null) {
            content = dataUri;
        } else {
            content = documentTextFallback(b).orElseGet(() -> fallbackLabel(b, kind));
        }
        return MAPPER.createObjectNode().put("type", "text").put("content", content);
    }

    /**
     * The {@link PdfTextExtractor#DOCUMENT_TEXT_UNAVAILABLE_MARKER} means extraction failed, so it is not real content.
     */
    private static Optional<String> documentTextFallback(ContentBlock b) {
        if (!b.isDocument()) return Optional.empty();
        String text = b.text();
        if (text == null || text.isBlank() || PdfTextExtractor.DOCUMENT_TEXT_UNAVAILABLE_MARKER.equals(text)) {
            return Optional.empty();
        }
        return Optional.of("[document: " + defaultedMediaType(b) + "]\n" + text);
    }

    /**
     * Null when the bytes cannot be recovered without a network fetch: a {@code *_url} is inlined only when it is
     * already a {@code data:} URI, and a {@code *_ref} needs the store.
     */
    private static @Nullable String inlineDataUri(
            ContentBlock b, @Nullable MediaStore mediaStore, @Nullable String projectId) {
        return switch (b.type()) {
            case ContentBlock.TYPE_IMAGE_B64, ContentBlock.TYPE_DOCUMENT_B64 -> {
                if (b.data() == null) yield null;
                yield "data:" + defaultedMediaType(b) + ";base64," + b.data();
            }
            case ContentBlock.TYPE_IMAGE_URL, ContentBlock.TYPE_DOCUMENT_URL -> {
                String u = b.url();
                yield u != null && u.regionMatches(true, 0, "data:", 0, 5) ? u : null;
            }
            default -> {
                if (mediaStore == null || projectId == null || b.data() == null) yield null;
                Optional<StoredMedia> stored = mediaStore.get(projectId, new MediaRef(b.data()));
                if (stored.isEmpty()) yield null;
                StoredMedia m = stored.get();
                String mime = m.mediaType() == null || m.mediaType().isBlank() ? defaultedMediaType(b) : m.mediaType();
                yield "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(m.bytes());
            }
        };
    }

    private static String defaultedMediaType(ContentBlock b) {
        // Read the accessor once: SpotBugs treats a second {@code b.mediaType()} after the null guard as a fresh
        // @Nullable return (NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE).
        String mediaType = b.mediaType();
        if (mediaType != null && !mediaType.isBlank()) return mediaType;
        return b.isDocument() ? "application/pdf" : "image/png";
    }

    private static String fallbackLabel(ContentBlock b, String kind) {
        String url = b.url();
        if (url != null) return "[" + kind + ": " + url + "]";
        return "[" + kind + " omitted: " + defaultedMediaType(b) + "]";
    }

    private static ObjectNode textMessage(String role, String content, boolean addFinishReason) {
        ObjectNode msg = MAPPER.createObjectNode();
        msg.put("role", role == null || role.isBlank() ? "user" : role);
        ArrayNode parts = msg.putArray("parts");
        ObjectNode part = parts.addObject();
        part.put("type", "text");
        part.put("content", content == null ? "" : content);
        if (addFinishReason) msg.put("finish_reason", "end_turn");
        return msg;
    }

    private static boolean hasRole(JsonNode array) {
        for (JsonNode el : array) {
            if (el.isObject() && el.has("role")) return true;
        }
        return false;
    }

    private static @Nullable JsonNode tryParse(@Nullable String payload) {
        if (payload == null) return null;
        String trimmed = payload.stripLeading();
        if (trimmed.isEmpty() || (trimmed.charAt(0) != '[' && trimmed.charAt(0) != '{')) return null;
        try {
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private static String writeArray(ArrayNode node) {
        return node.toString();
    }

    /** Adopts the standard key where one exists and accepts common provider aliases; absent values are not emitted. */
    private static void emitUsageAndTool(ObjectNode attrs, @Nullable Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return;
        putLong(
                attrs,
                GenAiAttributes.USAGE_INPUT_TOKENS,
                metadata,
                GenAiAttributes.USAGE_INPUT_TOKENS,
                "input_tokens",
                "prompt_tokens");
        putLong(
                attrs,
                GenAiAttributes.USAGE_OUTPUT_TOKENS,
                metadata,
                GenAiAttributes.USAGE_OUTPUT_TOKENS,
                "output_tokens",
                "completion_tokens");
        putString(attrs, GenAiAttributes.TOOL_NAME, metadata, GenAiAttributes.TOOL_NAME, "tool.name", "tool_name");
        putString(
                attrs,
                GenAiAttributes.TOOL_CALL_ID,
                metadata,
                GenAiAttributes.TOOL_CALL_ID,
                "tool.call.id",
                "tool_call_id");
    }

    private static void putLong(ObjectNode attrs, String attrKey, Map<String, Object> metadata, String... metaKeys) {
        for (String k : metaKeys) {
            Object v = metadata.get(k);
            if (v instanceof Number n) {
                attrs.put(attrKey, n.longValue());
                return;
            }
            if (v instanceof String s && !s.isBlank()) {
                try {
                    attrs.put(attrKey, Long.parseLong(s.trim()));
                    return;
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private static void putString(ObjectNode attrs, String attrKey, Map<String, Object> metadata, String... metaKeys) {
        for (String k : metaKeys) {
            Object v = metadata.get(k);
            if (v instanceof String s && !s.isBlank()) {
                attrs.put(attrKey, s);
                return;
            }
        }
    }

    /** Accepts {@code gen_ai.system} or {@code gen_ai.provider.name}. */
    private static @Nullable String declaredSystem(@Nullable Map<String, Object> metadata) {
        if (metadata == null) return null;
        for (String k : new String[] {GenAiAttributes.SYSTEM, GenAiAttributes.PROVIDER_NAME}) {
            if (metadata.get(k) instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    public static String inferSystem(@Nullable String model) {
        if (model == null) return "other";
        String m = model.toLowerCase(Locale.ROOT);
        if (m.contains("claude") || m.contains("anthropic")) return "anthropic";
        if (m.startsWith("gpt")
                || m.startsWith("o1")
                || m.startsWith("o3")
                || m.startsWith("o4")
                || m.contains("openai")) return "openai";
        if (m.contains("gemini") || m.contains("google")) return "google";
        return "other";
    }
}
