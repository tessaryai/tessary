// SPDX-License-Identifier: Apache-2.0
package ai.tessary.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Readers of a stored input/output payload: text views that unwrap the role-tagged gen_ai envelope, and a block view
 * that keeps image and document parts as typed media.
 */
public final class ContentExtractor {

    // A non-Spring static utility; the @Primary bean has identical strict semantics.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContentExtractor() {}

    /**
     * The single definition of "is this the envelope", shared by {@link #columnText} and
     * {@code MalformedOutputDetector}.
     */
    public static boolean isMessageEnvelope(@Nullable JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) return false;
        for (JsonNode msg : node) {
            if (msg.isObject() && msg.has("role")) return true;
        }
        return false;
    }

    /**
     * The ONE place the envelope is unwrapped for text consumers, so no classifier scores raw {@code [{"role":…}]} JSON
     * (a prompt-injection encoder fired on the structure itself). Falls back to every message when {@code role} is
     * absent; never throws.
     */
    public static String columnText(@Nullable String raw, String role) {
        return columnTextForRoles(raw, Set.of(role));
    }

    /**
     * For callers needing several roles, such as Groundedness, whose source content may sit in the system or the user
     * message. Message order is preserved.
     */
    public static String columnTextForRoles(@Nullable String raw, Set<String> roles) {
        if (raw == null || raw.isBlank()) return "";
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            return raw;
        }
        if (!isMessageEnvelope(node)) return flattenContentText(node);
        StringBuilder matched = new StringBuilder();
        StringBuilder all = new StringBuilder();
        for (JsonNode msg : node) {
            if (!msg.isObject()) continue;
            String text = messageContentText(msg);
            if (text.isEmpty()) continue;
            appendLine(all, text);
            if (roles.contains(msg.path("role").asText(""))) appendLine(matched, text);
        }
        return matched.length() > 0 ? matched.toString() : all.toString();
    }

    public record RoleMessage(String role, String text) {}

    /**
     * Unlike {@link #columnText}, keeps each message and NEVER falls back to unlisted roles, so {@code system} and tool
     * messages stay out. A plain string becomes one message under {@code fallbackRole}.
     */
    public static List<RoleMessage> columnMessages(@Nullable String raw, Set<String> roles, String fallbackRole) {
        if (raw == null || raw.isBlank()) return List.of();
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            return List.of(new RoleMessage(fallbackRole, raw.strip()));
        }
        if (!isMessageEnvelope(node)) {
            String text = flattenContentText(node);
            return text.isBlank() ? List.of() : List.of(new RoleMessage(fallbackRole, text));
        }
        List<RoleMessage> out = new ArrayList<>();
        for (JsonNode msg : node) {
            if (!msg.isObject()) continue;
            String role = msg.path("role").asText("");
            if (!roles.contains(role)) continue;
            String text = messageThreadText(msg);
            if (!text.isBlank()) out.add(new RoleMessage(role, text));
        }
        return List.copyOf(out);
    }

    /** Non-text parts render as placeholders, so a reader still sees an attachment was present. */
    private static String messageThreadText(JsonNode msg) {
        JsonNode content = msg.get("content");
        if (content != null && !content.isNull()) return partsThreadText(content);
        JsonNode parts = msg.get("parts");
        if (parts != null && parts.isArray()) return partsThreadText(parts);
        return "";
    }

    private static String partsThreadText(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isObject()) return partPlaceholder(node);
        if (!node.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : node) {
            String token = partPlaceholder(part);
            if (!token.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(token);
            }
        }
        return sb.toString();
    }

    /**
     * Tool parts become a terse outcome marker (a successful {@code tool_result} collapses to {@code ""}); other
     * non-text parts become typed placeholders.
     */
    public static String partPlaceholder(@Nullable JsonNode part) {
        if (part == null || part.isNull()) return "";
        if (part.isTextual()) return part.asText().strip();
        if (!part.isObject()) return "";
        String type = part.path("type").asText("");
        switch (type) {
            case "", "text", "reasoning", "thinking" -> {
                return partTextField(part).strip();
            }
            case "image", "image_url", "input_image", "output_image", ContentBlock.TYPE_IMAGE_REF -> {
                String caption = firstNonBlank(part, "caption", "alt").strip();
                return caption.isEmpty() ? "[image]" : "[image: " + caption + "]";
            }
            // The three ContentBlock document kinds route here too; document_url carries only a "url", so it would
            // otherwise degrade to "[unsupported]".
            case "file",
                    "document",
                    "input_file",
                    ContentBlock.TYPE_DOCUMENT_REF,
                    ContentBlock.TYPE_DOCUMENT_B64,
                    ContentBlock.TYPE_DOCUMENT_URL -> {
                String name = firstNonBlank(part, "name", "filename").strip();
                return name.isEmpty() ? "[file]" : "[file: " + name + "]";
            }
            case "tool_call", "tool_use" -> {
                return toolMarker(part.path("name").asText(""), partError(part));
            }
            case "tool_result", "tool_call_response" -> {
                String error = partError(part);
                // Only errors carry signal beyond the paired tool_call's ok.
                return error == null ? "" : toolMarker("", error);
            }
            default -> {
                String text = partTextField(part).strip();
                return text.isEmpty() ? "[unsupported]" : text;
            }
        }
    }

    private static final int ERROR_SNIPPET_MAX = 120;

    // ASCII-only \s would leave NBSP intact.
    private static final java.util.regex.Pattern WHITESPACE =
            java.util.regex.Pattern.compile("\\s+", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);

    private static String toolMarker(@Nullable String name, @Nullable String error) {
        String n = name == null ? "" : name.strip();
        String head = n.isEmpty() ? "tool" : "tool:" + n;
        return error == null || error.isBlank()
                ? "[" + head + " ok]"
                : "[" + head + " error: " + errorSnippet(error) + "]";
    }

    /** Truncates by code point so an emoji at the boundary is never split. */
    private static String errorSnippet(String msg) {
        String collapsed = WHITESPACE.matcher(msg).replaceAll(" ").strip();
        int cps = collapsed.codePointCount(0, collapsed.length());
        if (cps <= ERROR_SNIPPET_MAX) return collapsed;
        return collapsed.substring(0, collapsed.offsetByCodePoints(0, ERROR_SNIPPET_MAX));
    }

    /** Null when the part succeeded. */
    @Nullable
    private static String partError(JsonNode part) {
        if (part.path("is_error").asBoolean(false)) {
            String msg = firstNonBlank(part, "error", "error_type", "content", "result", "response");
            return msg.isBlank() ? "error" : msg;
        }
        String flagged = firstNonBlank(part, "error", "error_type");
        return flagged.isBlank() ? null : flagged;
    }

    private static String partTextField(JsonNode part) {
        JsonNode text = part.get("text");
        if (text != null && text.isTextual()) return text.asText();
        JsonNode content = part.get("content");
        if (content != null && content.isTextual()) return content.asText();
        return "";
    }

    private static String firstNonBlank(JsonNode part, String... fields) {
        for (String f : fields) {
            JsonNode v = part.get(f);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return "";
    }

    public static String messageContentText(JsonNode msg) {
        JsonNode content = msg.get("content");
        if (content != null && !content.isNull()) return flattenContentText(content);
        JsonNode parts = msg.get("parts");
        if (parts != null && parts.isArray()) return flattenContentText(parts);
        return "";
    }

    private static void appendLine(StringBuilder sb, String text) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(text);
    }

    /** Shared by {@code TraceSpanMapper} and the column text views so they agree. Non-text parts are dropped. */
    public static String flattenContentText(@Nullable JsonNode content) {
        if (content == null || content.isNull()) return "";
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String t = partText(part);
                if (!t.isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        if (content.isObject()) {
            String t = partText(content);
            return t.isEmpty() ? content.toString() : t;
        }
        return content.asText("");
    }

    private static String partText(@Nullable JsonNode part) {
        if (part == null) return "";
        if (part.isTextual()) return part.asText();
        String t = fieldText(part.get("text"));
        if (!t.isEmpty()) return t;
        return fieldText(part.get("content"));
    }

    private static String fieldText(@Nullable JsonNode node) {
        if (node == null) return "";
        if (node.isTextual()) return node.asText();
        if (node.isObject() || node.isArray()) return node.toString();
        return "";
    }

    /**
     * The structured counterpart of {@link #flattenContentText(JsonNode)}, keeping image and document parts as typed
     * blocks.
     */
    public static List<ContentBlock> blocksFromContent(@Nullable JsonNode content) {
        if (content == null || content.isNull()) return List.of();
        if (content.isTextual()) {
            String t = content.asText();
            return t.isEmpty() ? List.of() : List.of(ContentBlock.text(t));
        }
        List<ContentBlock> out = new ArrayList<>();
        if (content.isArray()) {
            for (JsonNode part : content) flattenPart(part, out);
        } else if (content.isObject()) {
            flattenPart(content, out);
        }
        return out;
    }

    private static void flattenPart(@Nullable JsonNode part, List<ContentBlock> out) {
        if (part == null || part.isNull()) return;
        String type = part.isObject() ? part.path("type").asText("") : "";
        if ("image".equals(type) || "document".equals(type)) {
            flattenAnthropicPart(part, out);
        } else {
            flattenOpenAiPart(part, out);
        }
    }

    private static void flattenOpenAiPart(JsonNode part, List<ContentBlock> out) {
        String type = part.path("type").asText("");
        switch (type) {
            case "text" -> {
                String t = fieldText(part.get("text"));
                if (!t.isEmpty()) out.add(ContentBlock.text(t));
            }
            case "image_url" -> {
                JsonNode iu = part.get("image_url");
                String url =
                        iu != null && iu.isObject() ? iu.path("url").asText("") : (iu != null ? iu.asText("") : "");
                if (!url.isEmpty()) out.add(ContentBlock.imageUrl(url));
            }
            case "input_image", "output_image" -> {
                String url = part.path("image_url").asText(part.path("url").asText(""));
                if (!url.isEmpty()) out.add(ContentBlock.imageUrl(url));
            }
            // OpenAI document input: {type:"input_file"|"file", file_data:"data:<mime>;base64,..."} (a
            // data: URI, parsed the same way image_url's data-URI path is) or a bare url/file_url field.
            // file_id (the OpenAI Files API) is unsupported and falls through to `default` unchanged,
            // same as any other unrecognized shape today: PDF bytes or a URL only, no Files API.
            case "input_file", "file" -> {
                String fileData = part.path("file_data").asText("");
                DataUriPart du = parseDataUriPart(fileData);
                String url = firstNonBlank(part, "url", "file_url");
                if (du != null) {
                    out.add(ContentBlock.documentB64(du.data(), du.mime()));
                } else if (!url.isEmpty()) {
                    out.add(ContentBlock.documentUrl(url));
                } else {
                    // Not a data: URI or URL (e.g. an OpenAI file_id): dumped as text so it stays visible.
                    out.add(ContentBlock.text(part.toString()));
                }
            }
            case ContentBlock.TYPE_IMAGE_REF -> flattenImageRefPart(part, out);
            case ContentBlock.TYPE_DOCUMENT_REF -> flattenDocumentRefPart(part, out);
            default -> {
                // Unknown part type: dumped as text so it stays visible.
                out.add(ContentBlock.text(part.toString()));
            }
        }
    }

    /** The payload stays base64: {@link ContentBlock#documentB64} stores it verbatim. */
    private record DataUriPart(String mime, String data) {}

    private static @Nullable DataUriPart parseDataUriPart(String uri) {
        if (uri.isEmpty() || !uri.regionMatches(true, 0, "data:", 0, 5)) return null;
        int comma = uri.indexOf(',');
        if (comma < 0) return null;
        String header = uri.substring(5, comma);
        if (!header.toLowerCase(java.util.Locale.ROOT).contains(";base64")) return null;
        String mime = header.substring(0, header.indexOf(';'));
        return new DataUriPart(mime.isBlank() ? "application/pdf" : mime, uri.substring(comma + 1));
    }

    private static void flattenAnthropicPart(JsonNode part, List<ContentBlock> out) {
        String type = part.path("type").asText("");
        // Only base64 and url sources are handled.
        boolean image = "image".equals(type);
        if (!image && !"document".equals(type)) return;
        JsonNode src = part.get("source");
        if (src == null || !src.isObject()) return;
        String stype = src.path("type").asText("");
        if ("base64".equals(stype)) {
            String mt = src.path("media_type").asText(image ? "image/png" : "application/pdf");
            String data = src.path("data").asText("");
            if (!data.isEmpty()) out.add(image ? ContentBlock.imageB64(data, mt) : ContentBlock.documentB64(data, mt));
        } else if ("url".equals(stype)) {
            String url = src.path("url").asText("");
            if (!url.isEmpty()) out.add(image ? ContentBlock.imageUrl(url) : ContentBlock.documentUrl(url));
        }
    }

    /** The node {@code MediaExternalizer} writes at ingest, re-parsed into {@link ContentBlock#imageRef}. */
    private static void flattenImageRefPart(JsonNode part, List<ContentBlock> out) {
        String mediaId = part.path("data").asText("");
        if (!mediaId.isEmpty()) {
            out.add(ContentBlock.imageRef(mediaId, part.path("mediaType").asText("image/png")));
        }
    }

    /**
     * Without this a persisted {@code document_ref} would re-parse as JSON text. {@code text} carries the extracted PDF
     * text so readers need no MediaStore round trip.
     */
    private static void flattenDocumentRefPart(JsonNode part, List<ContentBlock> out) {
        String mediaId = part.path("data").asText("");
        if (!mediaId.isEmpty()) {
            String mediaType = part.path("mediaType").asText("application/pdf");
            String text = part.path("text").asText(null);
            out.add(
                    text == null || text.isEmpty()
                            ? ContentBlock.documentRef(mediaId, mediaType)
                            : new ContentBlock(ContentBlock.TYPE_DOCUMENT_REF, text, null, mediaId, mediaType));
        }
    }
}
