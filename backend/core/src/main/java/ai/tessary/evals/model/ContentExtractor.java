// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Translate an arbitrary upstream "output" payload (text or JSON-serialized
 * message structure) into a list of {@link ContentBlock}.
 *
 * <p>Shapes recognised (vendor-neutral — keyed on the JSON structure, not the source):
 * <ol>
 *   <li>Plain string — emits a single text block verbatim.</li>
 *   <li>OpenAI-style messages array: {@code [{role, content: ... }]}.
 *       Each message's {@code content} is flattened: a string becomes a text block;
 *       an array of {@code {type:"text"|"image_url", ...}} parts becomes the
 *       respective blocks. Non-assistant turns are still included so the judge
 *       sees the whole transcript when present (we don't strip user turns —
 *       upstream stored what it stored).</li>
 *   <li>OTel gen_ai semconv messages array: {@code [{role, parts:[{type, ...}]}]} —
 *       the shape stored from {@code gen_ai.input.messages}/{@code gen_ai.output.messages}.
 *       Each part is unwrapped to a first-class typed block: {@code text}→text,
 *       {@code reasoning}→reasoning (empty dropped), {@code tool_call}→tool_call,
 *       {@code tool_result}/{@code tool_call_response}→tool_result, image refs→image.
 *       See {@link #genAiPartsBlocks} — the same helper ingest uses to persist message
 *       blocks, so the persisted view and the judge view never diverge.</li>
 *   <li>Anthropic-style content array: {@code [{type:"text"|"image"|"tool_use"|
 *       "tool_result"|"thinking", ...}]}. {@code image.source.type=="base64"} becomes an
 *       {@code image_b64} block; {@code image.source.type=="url"} becomes an {@code image_url}
 *       block; tool/thinking parts become their typed blocks.</li>
 * </ol>
 *
 * If parsing fails or the JSON shape is unrecognized, the entire payload is
 * returned as one text block. This is "first do no harm": grading still
 * proceeds, just with reduced fidelity.
 */
public final class ContentExtractor {

    // Deliberately a static bare mapper, not the shared JacksonConfig bean: this is a
    // non-Spring static utility, and the @Primary bean has identical strict semantics.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContentExtractor() {}

    /**
     * True when {@code node} is a gen_ai message envelope: a non-empty array carrying at least one
     * role-tagged object ({@code [{"role":…,"content"|"parts":…}, …]}) — the shape the OTLP receiver
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
     * String overload of {@link #isMessageEnvelope(JsonNode)}: true when {@code raw} parses to a
     * role-tagged gen_ai message envelope. A plain-string blob or unparseable / non-envelope JSON is
     * {@code false} — used by the judge-view builder to decide whether to unwrap into typed blocks or
     * ride the payload through verbatim.
     */
    public static boolean isMessageEnvelope(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            return isMessageEnvelope(MAPPER.readTree(raw));
        } catch (JsonProcessingException e) {
            return false;
        }
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
     * downstream text feature — encoder classifiers, regex signals, embeddings — ever scores the raw
     * {@code [{"role":…}]} JSON. That is the class of bug where a prompt-injection encoder reads the
     * envelope <em>structure itself</em> as an injection and fires on benign traffic.
     */
    public static String columnText(@Nullable String raw, String role) {
        return columnTextForRoles(raw, Set.of(role));
    }

    /**
     * The {@link #columnText} generalization for callers that need MORE than one role's text
     * combined — e.g. the Groundedness built-in's premise, where source content commonly lives in
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
            return raw; // a plain-string blob from a provider adapter — already text, not JSON
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

    /** One role-tagged message's flattened text — the ordered element of {@link #columnMessages}. */
    public record RoleMessage(String role, String text) {}

    /**
     * The per-message, multi-role sibling of {@link #columnText}: the ordered role+text of a stored
     * gen_ai column, keeping only messages whose role is in {@code roles} and preserving message
     * order. Unlike {@link #columnText} (which collapses one role's text to a single string and falls
     * back to <em>every</em> message when that role is absent), this keeps each message distinct and
     * NEVER falls back to unlisted roles — so a caller asking for {@code {user, assistant}} reliably
     * excludes {@code system}/{@code developer}/tool messages. A plain-string blob or unrecognized
     * JSON yields a single message under {@code fallbackRole} (the column's default speaker). Empty
     * messages are dropped; {@code []} for null/blank.
     *
     * <p>Added for the {@link ai.tessary.evals.classifier.substrate.ConversationThreadAssembler} dialogue view,
     * which needs both speakers rendered as distinct turns rather than one role's text.
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
     * A message's text for the conversation-thread view: like {@link #messageContentText} but
     * multimodal-aware — non-text parts (images, files, tool calls/results, unknown structured parts)
     * render as typed placeholders ({@link #partPlaceholder}) instead of being dropped, so a text
     * encoder scoring the thread still sees that an attachment was present in that turn. Contract v2;
     * the placeholder/marker vocabulary is shared with Python {@code render_part}.
     */
    public static String messageThreadText(JsonNode msg) {
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
     * One content part rendered for the conversation-thread view (contract v2): text parts return their
     * text; tool parts return a terse outcome marker ({@link #toolMarker} — {@code [tool:<name> ok]} or
     * {@code [tool:<name> error: <snippet>]}, a successful {@code tool_result} collapsing to {@code ""});
     * every other non-text part returns a typed placeholder so the encoder knows it was present without
     * the raw payload. Handles both the normalized contract shape ({@code {type,caption/name/text}}) and
     * the vendor gen_ai/OpenAI/Anthropic shapes. The vocabulary is the cross-language contract (mirrored
     * by Python {@code render_part} and pinned by {@code context_contract.json}).
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
            // "file"/"document"/"input_file" is the third-party/OTel placeholder vocabulary; the three
            // ContentBlock document kinds route here too (mirroring Python's _FILE_TYPES) so a persisted
            // document_ref/document_b64/document_url node renders the same terse placeholder rather than
            // falling to "default" — document_url in particular carries only a "url" field, never
            // "text"/"content", so leaving it out of this list means partTextField finds nothing and it
            // silently degrades to "[unsupported]" instead of "[file]", a train/serve divergence from the
            // Python mirror. Neither node carries a "name"/"filename" JSON key (ContentBlock has no
            // filename field — see its class doc), so this bottoms out at the bare "[file]" label, exactly
            // mirroring image_ref's bare "[image]" fallback just above (image_ref carries no
            // "caption"/"alt" key either).
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

    // UNICODE_CHARACTER_CLASS so \s matches the full Unicode whitespace set (NBSP, etc.), like Python's
    // re \s on a str pattern — ASCII-only \s would leave NBSP intact and diverge from the Python renderer.
    private static final java.util.regex.Pattern WHITESPACE =
            java.util.regex.Pattern.compile("\\s+", java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * A tool outcome as a terse thread marker (contract v2): {@code [tool:<name> ok]} on success,
     * {@code [tool:<name> error: <snippet>]} on error. A blank name drops to {@code [tool ok]} /
     * {@code [tool error: …]}. Mirrors Python {@code context.tool_marker}; the single definition
     * shared by the inline-part view ({@link #partPlaceholder}) and the standalone tool-observation
     * turn ({@code ConversationThreadRenderer.reduceThread}).
     */
    public static String toolMarker(@Nullable String name, @Nullable String error) {
        String n = name == null ? "" : name.strip();
        String head = n.isEmpty() ? "tool" : "tool:" + n;
        return error == null || error.isBlank()
                ? "[" + head + " ok]"
                : "[" + head + " error: " + errorSnippet(error) + "]";
    }

    /**
     * A tool error rendered terse: whitespace-collapsed (Unicode-aware), stripped, head-truncated to
     * {@value #ERROR_SNIPPET_MAX} CODE POINTS. Truncation counts and splits by code point (Python slices
     * {@code [:120]} by code point), so an emoji at the boundary is kept or dropped whole — a UTF-16
     * {@code substring} could split its surrogate pair and diverge from the Python renderer.
     */
    public static String errorSnippet(String msg) {
        String collapsed = WHITESPACE.matcher(msg).replaceAll(" ").strip();
        int cps = collapsed.codePointCount(0, collapsed.length());
        if (cps <= ERROR_SNIPPET_MAX) return collapsed;
        return collapsed.substring(0, collapsed.offsetByCodePoints(0, ERROR_SNIPPET_MAX));
    }

    /**
     * The error text a tool part carries, or {@code null} when it succeeded: a part errs when it sets
     * {@code is_error:true} or carries a non-blank {@code error}/{@code error_type} string. When flagged
     * without a message the payload fields ({@code content}/{@code result}/{@code response}) supply one,
     * falling back to the literal {@code "error"}. Mirrors Python {@code context._part_error}.
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
     * text of any structured parts. Handles the common shapes seen on the judge /
     * export boundary: a bare string; an OpenAI/Anthropic parts array of
     * {@code {type:"text", text|content:"…"}}; or an object carrying a
     * {@code text}/{@code content} field. Non-text parts (images, tool calls) are
     * dropped from the text view. Returns {@code ""} for null/empty.
     *
     * <p>Shared by {@code TraceSpanMapper} so the OTel export and the judge agree
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
     * Extract the {@link ContentBlock}s from an already-parsed message-content node — the
     * structured counterpart to {@link #flattenContentText(JsonNode)}, which collapses the
     * same node to plain text and drops images. The export path ({@code TraceSpanMapper})
     * uses this to emit typed/labeled image parts instead of losing them in the text
     * flatten. A bare string yields a single text block; an OpenAI/Anthropic parts array
     * yields per-part text + image blocks (same branches as {@link #extract(String)}).
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

    /** True if any block carries media (an image or a document). Callers that build a "verbatim"
     *  judge view keep the extracted blocks (with their first-class media parts) for media-bearing
     *  units rather than splicing raw base64/text-extraction into a text block. Renamed from
     *  {@code hasImages} when the document modality landed (#985) — {@link ContentBlock#isMedia()} is
     *  the single source of truth this delegates to, so a document-only unit is no longer
     *  misclassified as "no media" and routed to the wrong ({@code rawBlocks}) branch by
     *  {@code TraceTransformer.judgeView}. */
    public static boolean hasMedia(@Nullable List<ContentBlock> blocks) {
        if (blocks == null) return false;
        for (ContentBlock b : blocks) {
            if (b != null && b.isMedia()) return true;
        }
        return false;
    }

    public static List<ContentBlock> extract(@Nullable String payload) {
        if (payload == null || payload.isEmpty()) return List.of();

        // Cheap pre-check: if it doesn't look like JSON, return as text.
        String trimmed = payload.stripLeading();
        if (trimmed.isEmpty() || (trimmed.charAt(0) != '[' && trimmed.charAt(0) != '{')) {
            return List.of(ContentBlock.text(payload));
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (Exception e) {
            return List.of(ContentBlock.text(payload));
        }

        List<ContentBlock> out = new ArrayList<>();
        if (root.isArray()) {
            // Either OpenAI messages array (has "role") or Anthropic content array (has "type" only).
            boolean hasRoles = false;
            for (JsonNode n : root) {
                if (n.has("role")) {
                    hasRoles = true;
                    break;
                }
            }
            if (hasRoles) {
                for (JsonNode msg : root) flattenOpenAiMessage(msg, out);
            } else {
                for (JsonNode part : root) flattenAnthropicPart(part, out);
            }
        } else if (root.isObject()) {
            // Single OpenAI message wrapped in an object, or a single Anthropic part.
            if (root.has("role")) {
                flattenOpenAiMessage(root, out);
            } else if (root.has("type")) {
                flattenAnthropicPart(root, out);
            } else {
                return List.of(ContentBlock.text(payload));
            }
        }

        if (out.isEmpty()) return List.of(ContentBlock.text(payload));
        return List.copyOf(out);
    }

    // --- OpenAI shape ---------------------------------------------------------

    private static void flattenOpenAiMessage(JsonNode msg, List<ContentBlock> out) {
        JsonNode content = msg.get("content");
        if (content == null || content.isNull()) {
            // The OTel gen_ai semconv envelope carries the message body in `parts`, not `content`
            // ({@code [{role, parts:[{type,content|arguments,…}]}]}). Unwrap it into typed
            // reasoning/tool_call/tool_result blocks, the same way ingest persists them.
            JsonNode parts = msg.get("parts");
            if (parts != null && parts.isArray()) {
                out.addAll(genAiPartsBlocks(parts));
                return;
            }
            // The assistant message may have only tool_calls and no content — emit typed tool_call blocks.
            JsonNode toolCalls = msg.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    JsonNode fn = tc.get("function");
                    String name = fn != null
                            ? fn.path("name").asText("")
                            : tc.path("name").asText("");
                    String args = fn != null ? fn.path("arguments").asText("") : "";
                    out.add(ContentBlock.toolCall(name + "(" + args + ")", tc.toString()));
                }
            } else if (toolCalls != null && !toolCalls.isNull()) {
                out.add(ContentBlock.text("tool_calls: " + toolCalls));
            }
            return;
        }
        if (content.isTextual()) {
            String t = content.asText();
            if (!t.isEmpty()) out.add(ContentBlock.text(t));
            return;
        }
        if (content.isArray()) {
            for (JsonNode part : content) flattenOpenAiPart(part, out);
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
                // Newer OpenAI variants. Try common fields.
                String url = part.path("image_url").asText(part.path("url").asText(""));
                if (!url.isEmpty()) out.add(ContentBlock.imageUrl(url));
            }
            // OpenAI document input: {type:"input_file"|"file", file_data:"data:<mime>;base64,..."} (a
            // data: URI, parsed the same way image_url's data-URI path is) or a bare url/file_url field.
            // file_id (the OpenAI Files API) is unsupported and falls through to `default` unchanged —
            // same as any other unrecognized shape today (#985: PDF-bytes/URL only, no Files API).
            case "input_file", "file" -> {
                String fileData = part.path("file_data").asText("");
                DataUriPart du = parseDataUriPart(fileData);
                String url = firstNonBlank(part, "url", "file_url");
                if (du != null) {
                    out.add(ContentBlock.documentB64(du.data(), du.mime()));
                } else if (!url.isEmpty()) {
                    out.add(ContentBlock.documentUrl(url));
                } else {
                    // Neither a data: URI nor a URL — e.g. file_id (OpenAI Files API), unsupported for
                    // this run (#985: PDF-bytes/URL only). Same fallback as `default`: JSON-dumped text
                    // so the judge can still see it, rather than silently dropping the part.
                    out.add(ContentBlock.text(part.toString()));
                }
            }
            case ContentBlock.TYPE_IMAGE_REF -> flattenImageRefPart(part, out);
            case ContentBlock.TYPE_DOCUMENT_REF -> flattenDocumentRefPart(part, out);
            default -> {
                // Unknown part type — fall back to JSON-serialized text so the judge can still see it.
                out.add(ContentBlock.text(part.toString()));
            }
        }
    }

    /** A parsed {@code data:<mime>;base64,<payload>} URI's declared mime + raw base64 payload
     *  (NOT decoded — {@link ContentBlock#documentB64} stores base64 verbatim, unlike
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

    // --- OTel gen_ai `parts` shape --------------------------------------------

    /**
     * Typed blocks from the OTel gen_ai {@code parts:[{type,content|text|arguments,…}]} shape — the
     * canonical unwrap of the {@code [{role, parts:[…]}]} envelope. Structured parts
     * ({@code tool_call}/{@code tool_result}/{@code reasoning}) become first-class typed blocks — the
     * structured JSON is carried in {@link ContentBlock#data} and a readable summary in {@code text};
     * empty reasoning is dropped. Plain text parts stay text; an unrecognised part is preserved whole as
     * text (never dropped).
     *
     * <p>The canonical unwrap of the OTel gen_ai {@code parts} shape, shared by every reader of it —
     * the judge view ({@link #extract} via {@link #flattenOpenAiMessage}) and the ingest edge's
     * tool-call extraction — so the two can never diverge.
     */
    public static List<ContentBlock> genAiPartsBlocks(JsonNode parts) {
        List<ContentBlock> out = new ArrayList<>();
        if (parts == null || !parts.isArray()) return out;
        for (JsonNode part : parts) {
            if (part.isTextual()) {
                if (!part.asText().isEmpty()) out.add(ContentBlock.text(part.asText()));
                continue;
            }
            if (!part.isObject()) continue;
            switch (part.path("type").asText("")) {
                case "tool_call" -> {
                    String name = part.path("name").asText("");
                    JsonNode args = part.get("arguments");
                    String summary = name + (args != null && !args.isNull() ? "(" + args + ")" : "()");
                    out.add(ContentBlock.toolCall(summary, part.toString()));
                }
                // `tool_call_response` is the OTel gen_ai semconv name; `tool_result` is the
                // Anthropic-style spelling other sources emit. Treat them as the same block.
                case "tool_result", "tool_call_response" ->
                    out.add(ContentBlock.toolResult(genAiPartText(part), part.toString()));
                case "reasoning", "thinking" -> {
                    String text = genAiPartText(part);
                    if (!text.isEmpty()) out.add(ContentBlock.reasoning(text)); // drop empty reasoning
                }
                case ContentBlock.TYPE_IMAGE_REF -> flattenImageRefPart(part, out);
                case ContentBlock.TYPE_DOCUMENT_REF -> flattenDocumentRefPart(part, out);
                default -> {
                    String text = genAiPartText(part);
                    out.add(text.isEmpty() ? ContentBlock.text(part.toString()) : ContentBlock.text(text));
                }
            }
        }
        return out;
    }

    /** The textual payload of a gen_ai part, tolerant of the field names the spec uses across part
     *  types: {@code content} (text/reasoning), {@code result} / {@code response} (a
     *  {@code tool_call_response}), else {@code text}. A structured (object/array) value is serialized
     *  as JSON rather than dropped. */
    private static String genAiPartText(JsonNode part) {
        JsonNode t = firstPresent(part, "content", "result", "response", "text");
        if (t == null || t.isNull()) return "";
        return t.isTextual() ? t.asText() : t.toString();
    }

    /** The first of {@code fields} present (and non-null-node) on {@code part}, else null. */
    private static @Nullable JsonNode firstPresent(JsonNode part, String... fields) {
        for (String f : fields) {
            JsonNode v = part.get(f);
            if (v != null && !v.isNull()) return v;
        }
        return null;
    }

    // --- Anthropic shape ------------------------------------------------------

    private static void flattenAnthropicPart(JsonNode part, List<ContentBlock> out) {
        String type = part.path("type").asText("");
        switch (type) {
            case "text" -> {
                String t = fieldText(part.get("text"));
                if (!t.isEmpty()) out.add(ContentBlock.text(t));
            }
            case "image" -> {
                JsonNode src = part.get("source");
                if (src != null && src.isObject()) {
                    String stype = src.path("type").asText("");
                    if ("base64".equals(stype)) {
                        String mt = src.path("media_type").asText("image/png");
                        String data = src.path("data").asText("");
                        if (!data.isEmpty()) out.add(ContentBlock.imageB64(data, mt));
                    } else if ("url".equals(stype)) {
                        String url = src.path("url").asText("");
                        if (!url.isEmpty()) out.add(ContentBlock.imageUrl(url));
                    }
                }
            }
            // Anthropic document part: {type:"document", source:{type:"base64"|"url", ...}}. Only the
            // base64/url PDF sources are handled — Anthropic's text-source and content-array (citations)
            // document sources are explicitly out of scope for this run (PDF-bytes/URL only, #985).
            case "document" -> {
                JsonNode src = part.get("source");
                if (src != null && src.isObject()) {
                    String stype = src.path("type").asText("");
                    if ("base64".equals(stype)) {
                        String mt = src.path("media_type").asText("application/pdf");
                        String data = src.path("data").asText("");
                        if (!data.isEmpty()) out.add(ContentBlock.documentB64(data, mt));
                    } else if ("url".equals(stype)) {
                        String url = src.path("url").asText("");
                        if (!url.isEmpty()) out.add(ContentBlock.documentUrl(url));
                    }
                }
            }
            case "tool_use" -> {
                String name = part.path("name").asText("");
                JsonNode input = part.get("input");
                String summary = name + (input != null && !input.isNull() ? "(" + input + ")" : "()");
                out.add(ContentBlock.toolCall(summary, part.toString()));
            }
            case "tool_result" -> {
                JsonNode c = part.get("content");
                String content = c == null || c.isNull() ? "" : (c.isTextual() ? c.asText() : c.toString());
                out.add(ContentBlock.toolResult(content, part.toString()));
            }
            case "thinking", "reasoning" -> {
                String t = fieldText(part.has("thinking") ? part.get("thinking") : part.get("text"));
                if (!t.isEmpty()) out.add(ContentBlock.reasoning(t));
            }
            case ContentBlock.TYPE_IMAGE_REF -> flattenImageRefPart(part, out);
            case ContentBlock.TYPE_DOCUMENT_REF -> flattenDocumentRefPart(part, out);
            default -> {
                out.add(ContentBlock.text(part.toString()));
            }
        }
    }

    /**
     * An externalized image part ({@code {type:"image_ref", data:"<mediaId>", mediaType:"<mime>"}}) —
     * the {@link ContentBlock}-shaped node {@code MediaExternalizer} writes in place of inline base64 at
     * ingest. Both vendor branches route here so the persisted substrate's ref form re-parses back
     * into a {@link ContentBlock#imageRef}. The judge boundary used to re-hydrate that ref to bytes;
     * Track A removed grading, so the only re-hydration left is the media serve endpoint the trace
     * viewer calls with the id.
     */
    private static void flattenImageRefPart(JsonNode part, List<ContentBlock> out) {
        String mediaId = part.path("data").asText("");
        if (!mediaId.isEmpty()) {
            out.add(ContentBlock.imageRef(mediaId, part.path("mediaType").asText("image/png")));
        }
    }

    /**
     * An externalized document part ({@code {type:"document_ref", data:"<mediaId>", text:"<extracted
     * text>", mediaType:"<mime>"}}) — {@link #flattenImageRefPart}'s document counterpart. Without this
     * case a persisted {@code document_ref} node would fall to the {@code default} branch and re-parse
     * as a raw JSON-dumped text block instead of a real {@link ContentBlock#documentRef}, reintroducing
     * the #761-class round-trip bug for the new modality. {@code text} (the extracted PDF text, or a
     * failure marker) rides through unchanged so the judge boundary never needs a MediaStore round trip
     * for the common already-extracted case.
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
