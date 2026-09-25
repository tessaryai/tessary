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
 * Readers of a stored upstream input/output payload (plain text or a JSON message structure).
 *
 * <p>Text views: {@link #columnText} / {@link #columnTextForRoles} unwrap the role-tagged gen_ai
 * message envelope for one or more roles, {@link #columnMessages} keeps each message's role, and
 * {@link #flattenContentText} collapses a single message-content node to text.
 * {@link #partPlaceholder} renders one content part as text, with a marker or placeholder for a
 * non-text part.
 *
 * <p>Block view: {@link #blocksFromContent} turns a message-content node into {@link ContentBlock}s,
 * keeping OpenAI-style and Anthropic-style image and document parts as typed media blocks.
 */
public final class ContentExtractor {

    // Deliberately a static bare mapper, not the shared JacksonConfig bean: this is a
    // non-Spring static utility, and the @Primary bean has identical strict semantics.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContentExtractor() {}

    /**
     * True when {@code node} is a gen_ai message envelope: a non-empty array carrying at least one
     * role-tagged object ({@code [{"role":…,"content"|"parts":…}, …]}), the shape the OTLP receiver
     * stores verbatim in {@code observation.input}/{@code output} from {@code gen_ai.input.messages}
     * / {@code gen_ai.output.messages}. The single definition of "is this the envelope", shared by
     * the text view ({@link #columnText}) and schema validation ({@code MalformedOutputDetector}).
     */
    public static boolean isMessageEnvelope(@Nullable JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) return false;
        for (JsonNode msg : node) {
            if (msg.isObject() && msg.has("role")) return true;
        }
        return false;
    }

    /**
     * The plain-text view of a stored gen_ai input/output column, for the message {@code role} of
     * interest ({@code "user"} for a signal's INPUT, {@code "assistant"} for its OUTPUT). Parses
     * {@code raw}; if it is a {@linkplain #isMessageEnvelope message envelope}, returns the text of
     * the messages matching {@code role} (falling back to every message when that role is absent);
     * otherwise it flattens the bare payload, and a plain-string blob rides through verbatim. Never
     * throws; {@code ""} for null/blank.
     *
     * <p>This is the ONE place the role-tagged envelope is unwrapped for text consumers, so no
     * downstream text feature (encoder classifiers, regex signals, embeddings) ever scores the raw
     * {@code [{"role":…}]} JSON. That is the class of bug where a prompt-injection encoder reads the
     * envelope <em>structure itself</em> as an injection and fires on benign traffic.
     */
    public static String columnText(@Nullable String raw, String role) {
        return columnTextForRoles(raw, Set.of(role));
    }

    /**
     * The {@link #columnText} generalization for callers that need MORE than one role's text
     * combined, e.g. the Groundedness built-in's premise, where source content commonly lives in
     * either the system message ("Here is the document: …") or the user message (pasted inline),
     * and dropping whichever one isn't the single matched role would silently starve the premise.
     * Matches any message whose role is in {@code roles} (falling back to every message when NONE
     * of them are present, same posture as {@link #columnText}). Message order is preserved, not
     * grouped by role.
     */
    public static String columnTextForRoles(@Nullable String raw, Set<String> roles) {
        if (raw == null || raw.isBlank()) return "";
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            return raw; // a plain-string blob from a provider adapter, already text, not JSON
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

    /** One role-tagged message's flattened text, the ordered element of {@link #columnMessages}. */
    public record RoleMessage(String role, String text) {}

    /**
     * The per-message, multi-role sibling of {@link #columnText}: the ordered role+text of a stored
     * gen_ai column, keeping only messages whose role is in {@code roles} and preserving message
     * order. Unlike {@link #columnText} (which collapses one role's text to a single string and falls
     * back to <em>every</em> message when that role is absent), this keeps each message distinct and
     * NEVER falls back to unlisted roles, so a caller asking for {@code {user, assistant}} reliably
     * excludes {@code system}/{@code developer}/tool messages. A plain-string blob or unrecognized
     * JSON yields a single message under {@code fallbackRole} (the column's default speaker). Empty
     * messages are dropped; {@code []} for null/blank.
     */
    public static List<RoleMessage> columnMessages(@Nullable String raw, Set<String> roles, String fallbackRole) {
        if (raw == null || raw.isBlank()) return List.of();
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            String text = raw.strip();
            return text.isEmpty() ? List.of() : List.of(new RoleMessage(fallbackRole, text));
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

    /**
     * A message's text, multimodal-aware: like {@link #messageContentText}, but non-text parts (images,
     * files, tool calls/results, unknown structured parts) render as typed placeholders ({@link
     * #partPlaceholder}) instead of being dropped, so a reader still sees an attachment was present.
     */
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
     * One content part rendered as text: text parts return their text; tool parts return a terse outcome
     * marker ({@link #toolMarker}, {@code [tool:<name> ok]} or {@code [tool:<name> error: <snippet>]}, a
     * successful {@code tool_result} collapsing to {@code ""}); every other non-text part returns a typed
     * placeholder so a reader knows it was present without the raw payload. Handles both the normalized
     * contract shape ({@code {type,caption/name/text}}) and the vendor gen_ai/OpenAI/Anthropic shapes.
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
            // "file"/"document"/"input_file" is the third-party/OTel placeholder vocabulary; the three ContentBlock
            // document kinds route here too so a persisted document_ref/document_b64/document_url node renders the
            // same terse placeholder rather than falling to "default", document_url in particular carries only a
            // "url" field, never "text"/"content", so leaving it out of this list means partTextField finds nothing
            // and it silently degrades to "[unsupported]" instead of "[file]". Neither node carries a
            // "name"/"filename" JSON key (ContentBlock has no filename field), so this bottoms out
            // at the bare "[file]" label, exactly mirroring image_ref's bare "[image]" fallback just above (image_ref
            // carries no "caption"/"alt" key either).
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
                // A successful tool_result adds no trajectory beyond the paired tool_call's ok; only
                // errors carry signal, so a success collapses to "" and the error becomes a snippet.
                return error == null ? "" : toolMarker("", error);
            }
            default -> {
                String text = partTextField(part).strip();
                return text.isEmpty() ? "[unsupported]" : text;
            }
        }
    }

    /** Head cap on a rendered tool-error snippet (chars); a dumb truncation, never summarization. */
    private static final int ERROR_SNIPPET_MAX = 120;

    // UNICODE_CHARACTER_CLASS so \s matches the full Unicode whitespace set (NBSP, etc.); ASCII-only \s
    // would leave NBSP intact.
    private static final java.util.regex.Pattern WHITESPACE =
            java.util.regex.Pattern.compile("\\s+", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * A tool outcome as a terse marker: {@code [tool:<name> ok]} on success,
     * {@code [tool:<name> error: <snippet>]} on error. A blank name drops to {@code [tool ok]} /
     * {@code [tool error: …]}.
     */
    private static String toolMarker(@Nullable String name, @Nullable String error) {
        String n = name == null ? "" : name.strip();
        String head = n.isEmpty() ? "tool" : "tool:" + n;
        return error == null || error.isBlank()
                ? "[" + head + " ok]"
                : "[" + head + " error: " + errorSnippet(error) + "]";
    }

    /**
     * A tool error rendered terse: whitespace-collapsed (Unicode-aware), stripped, head-truncated to
     * {@value #ERROR_SNIPPET_MAX} CODE POINTS. Truncation counts and splits by code point, so an emoji
     * at the boundary is kept or dropped whole; a UTF-16 {@code substring} could split its surrogate
     * pair.
     */
    private static String errorSnippet(String msg) {
        String collapsed = WHITESPACE.matcher(msg).replaceAll(" ").strip();
        int cps = collapsed.codePointCount(0, collapsed.length());
        if (cps <= ERROR_SNIPPET_MAX) return collapsed;
        return collapsed.substring(0, collapsed.offsetByCodePoints(0, ERROR_SNIPPET_MAX));
    }

    /**
     * The error text a tool part carries, or {@code null} when it succeeded: a part errs when it sets
     * {@code is_error:true} or carries a non-blank {@code error}/{@code error_type} string. When flagged
     * without a message the payload fields ({@code content}/{@code result}/{@code response}) supply one,
     * falling back to the literal {@code "error"}.
     */
    @Nullable
    private static String partError(JsonNode part) {
        if (part.path("is_error").asBoolean(false)) {
            String msg = firstNonBlank(part, "error", "error_type", "content", "result", "response");
            return msg.isBlank() ? "error" : msg;
        }
        String flagged = firstNonBlank(part, "error", "error_type");
        return flagged.isBlank() ? null : flagged;
    }

    /** A part's {@code text} then {@code content} string field (structured values ignored), else "". */
    private static String partTextField(JsonNode part) {
        JsonNode text = part.get("text");
        if (text != null && text.isTextual()) return text.asText();
        JsonNode content = part.get("content");
        if (content != null && content.isTextual()) return content.asText();
        return "";
    }

    /** The first of {@code fields} present as a non-blank string on {@code part}, else "". */
    private static String firstNonBlank(JsonNode part, String... fields) {
        for (String f : fields) {
            JsonNode v = part.get(f);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return "";
    }

    /** One envelope message's text: its {@code content} (string or structured), else {@code parts[]}. */
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

    /**
     * Flatten an arbitrary message-content node to plain text, concatenating the
     * text of any structured parts. Handles the common shapes seen on the export
     * boundary: a bare string; an OpenAI/Anthropic parts array of
     * {@code {type:"text", text|content:"…"}}; or an object carrying a
     * {@code text}/{@code content} field. Non-text parts (images, tool calls) are
     * dropped from the text view. Returns {@code ""} for null/empty.
     *
     * <p>Shared by {@code TraceSpanMapper} and the column text views, so they agree
     * on how a message's content collapses to text.
     */
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

    /** A {@code text}/{@code content} field as text: a string verbatim, an object/array
     *  (structured-output delivered inside a text part) serialized as JSON, else empty. */
    private static String fieldText(@Nullable JsonNode node) {
        if (node == null) return "";
        if (node.isTextual()) return node.asText();
        if (node.isObject() || node.isArray()) return node.toString();
        return "";
    }

    /**
     * Extract the {@link ContentBlock}s from an already-parsed message-content node, the
     * structured counterpart to {@link #flattenContentText(JsonNode)}, which collapses the
     * same node to plain text and drops images. The export path ({@code TraceSpanMapper})
     * uses this to emit typed/labeled image parts instead of losing them in the text
     * flatten. A bare string yields a single text block; an OpenAI/Anthropic parts array
     * yields per-part text + image blocks.
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

    /**
     * Flatten one content part of either vendor shape into blocks. Routes the Anthropic
     * {@code {type:"image"|"document", source:{…}}} part to {@link #flattenAnthropicPart} (which
     * yields an image/document block) and everything else (OpenAI {@code image_url}/{@code
     * input_image}/{@code input_file}, plain text parts) to {@link #flattenOpenAiPart}. Used only by
     * {@link #blocksFromContent} on a message-level content node where the vendor shape isn't known
     * up front.
     */
    private static void flattenPart(@Nullable JsonNode part, List<ContentBlock> out) {
        if (part == null || part.isNull()) return;
        String type = part.isObject() ? part.path("type").asText("") : "";
        if ("image".equals(type) || "document".equals(type)) {
            flattenAnthropicPart(part, out);
        } else {
            flattenOpenAiPart(part, out);
        }
    }

    // --- OpenAI shape ---------------------------------------------------------

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
                // Newer OpenAI variants. Try common fields.
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
                    // Neither a data: URI nor a URL, e.g. file_id (OpenAI Files API), unsupported today
                    // (PDF bytes or a URL only). Same fallback as `default`: JSON-dumped text so it
                    // stays visible, rather than silently dropping the part.
                    out.add(ContentBlock.text(part.toString()));
                }
            }
            case ContentBlock.TYPE_IMAGE_REF -> flattenImageRefPart(part, out);
            case ContentBlock.TYPE_DOCUMENT_REF -> flattenDocumentRefPart(part, out);
            default -> {
                // Unknown part type, fall back to JSON-serialized text so it stays visible.
                out.add(ContentBlock.text(part.toString()));
            }
        }
    }

    /** A parsed {@code data:<mime>;base64,<payload>} URI's declared mime + raw base64 payload
     *  (NOT decoded, {@link ContentBlock#documentB64} stores base64 verbatim, unlike
     *  {@code MediaExternalizer} which decodes for storage). Null when {@code uri} is not a base64
     *  data URI. */
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

    // --- Anthropic shape ------------------------------------------------------

    private static void flattenAnthropicPart(JsonNode part, List<ContentBlock> out) {
        String type = part.path("type").asText("");
        // Anthropic image or document part: {type:"image"|"document", source:{type:"base64"|"url", ...}}.
        // Only the base64/url sources are handled; Anthropic's text-source and content-array
        // (citations) document sources are explicitly out of scope today (PDF bytes or a URL only).
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

    /**
     * An externalized image part ({@code {type:"image_ref", data:"<mediaId>", mediaType:"<mime>"}}),
     * the {@link ContentBlock}-shaped node {@code MediaExternalizer} writes in place of inline base64 at
     * ingest. {@link #flattenOpenAiPart} routes here so the persisted substrate's ref form re-parses
     * back into a {@link ContentBlock#imageRef}; re-hydrating the ref to bytes is the media serve endpoint
     * the trace viewer calls with the id.
     */
    private static void flattenImageRefPart(JsonNode part, List<ContentBlock> out) {
        String mediaId = part.path("data").asText("");
        if (!mediaId.isEmpty()) {
            out.add(ContentBlock.imageRef(mediaId, part.path("mediaType").asText("image/png")));
        }
    }

    /**
     * An externalized document part ({@code {type:"document_ref", data:"<mediaId>", text:"<extracted
     * text>", mediaType:"<mime>"}}), {@link #flattenImageRefPart}'s document counterpart. Without this
     * case a persisted {@code document_ref} node would fall to the {@code default} branch and re-parse
     * as a raw JSON-dumped text block instead of a real {@link ContentBlock#documentRef}, a round-trip
     * bug for this modality. {@code text} (the extracted PDF text, or a
     * failure marker) rides through unchanged so a reader never needs a MediaStore round trip for the
     * common already-extracted case.
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
