// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.export;

import ai.tessary.evals.ingest.GenAiAttributes;
import ai.tessary.evals.ingest.KindNormalizer;
import ai.tessary.evals.ingest.RawEntry;
import ai.tessary.evals.model.ContentBlock;
import ai.tessary.evals.model.ContentExtractor;
import ai.tessary.evals.open.media.MediaStore;
import ai.tessary.evals.open.media.MediaStore.MediaRef;
import ai.tessary.evals.open.media.MediaStore.StoredMedia;
import ai.tessary.evals.open.media.PdfTextExtractor;
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
 * Maps a platform {@link RawEntry} (one ingested observation) to a single
 * OpenTelemetry GenAI span, JSON-encoded as one JSONL line — the input format
 * the evals plugin's Path A consumes (<code>--traces traces.jsonl</code>).
 *
 * <p>The plugin reads only <code>gen_ai.*</code> attributes and accepts the
 * "messages-as-attributes" shape: <code>gen_ai.input.messages</code> /
 * <code>gen_ai.output.messages</code> are JSON-encoded arrays of
 * <code>{role, parts:[{type:"text", content}]}</code> (see the plugin's
 * {@code examples/sample_traces.jsonl}). {@code trace_id} is preserved verbatim
 * so the plugin's multi-turn grouping (spans sharing a trace) stays intact.
 *
 * <p><b>The ids that come out are now the ids that went in.</b> The substrate's own source
 * ({@code SubstrateSource}) hands this mapper the producer's trace id and the composite span handle, so
 * {@code context.trace_id} and {@code context.span_id} carry what the SDK actually emitted rather than
 * surrogates the platform minted. Nothing in this class changed to make that true — it always wrote
 * {@code raw.traceId()} and {@code raw.sourceExternalId()} through untouched — which is why the round trip
 * is exact rather than approximately preserved. Grouping is unaffected: it keys on {@code trace_id}, and
 * a raw OTel hex trace id groups exactly as a ULID did.
 */
public final class TraceSpanMapper {

    // Deliberately a static bare mapper, not the shared JacksonConfig bean: this is a
    // non-Spring static utility, and the @Primary bean has identical strict semantics.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TraceSpanMapper() {}

    /** {@link #toSpanLine(RawEntry, String, MediaStore, String)} with no media preservation — media
     *  blocks fall straight to their labeled placeholder (the pre-#986 behavior). Kept for the existing
     *  test call sites and any caller with no {@link MediaStore}/project id to hand; a real caller
     *  should use the four-arg overload so {@code image_ref}/{@code document_ref} blocks can inline. */
    public static String toSpanLine(RawEntry raw, String serviceName) {
        return toSpanLine(raw, serviceName, null, null);
    }

    /** One OTel GenAI span as a compact JSON string (no trailing newline). {@code mediaStore} +
     *  {@code projectId} let an {@code image_ref}/{@code document_ref} block re-hydrate to real bytes
     *  (see {@link #toSpan(RawEntry, String, MediaStore, String)}); pass {@code null} for both to keep
     *  such blocks as their honest {@code omitted} label instead. */
    public static String toSpanLine(
            RawEntry raw, String serviceName, @Nullable MediaStore mediaStore, @Nullable String projectId) {
        try {
            return MAPPER.writeValueAsString(toSpan(raw, serviceName, mediaStore, projectId));
        } catch (Exception e) {
            // Should not happen for an ObjectNode; fail loud rather than emit junk.
            throw new IllegalStateException("failed to serialize span for " + raw.sourceExternalId(), e);
        }
    }

    /** {@link #toSpan(RawEntry, String, MediaStore, String)} with no media preservation. See that
     *  overload's doc and {@link #toSpanLine(RawEntry, String)}'s note on when to prefer it. */
    public static ObjectNode toSpan(RawEntry raw, String serviceName) {
        return toSpan(raw, serviceName, null, null);
    }

    /**
     * Build the span. {@code mediaStore} + {@code projectId} are threaded through to
     * {@link #mediaPart} so an externalized {@code image_ref}/{@code document_ref} block can rehydrate
     * to real bytes for the export (#986) rather than staying a bare label; pass {@code null} for both
     * when no store is available (the block then keeps its honest {@code omitted} label — still
     * lossless-or-labeled, never a silent collapse).
     */
    public static ObjectNode toSpan(
            RawEntry raw, String serviceName, @Nullable MediaStore mediaStore, @Nullable String projectId) {
        // Prefer an authoritative provider/system declared upstream (e.g. an OpenInference llm.system threaded
        // into metadata by OpenInferenceNormalizer) over re-deriving it from the model name — otherwise a
        // declared provider whose model inferSystem can't recognize would silently collapse to "other".
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
        // Honor the canonical operation kind rather than hard-coding "chat": an invoke_agent/execute_tool entry
        // must re-emit with its truthful gen_ai.operation.name (defaults to "chat" when kind is unknown).
        attrs.put(GenAiAttributes.OPERATION_NAME, KindNormalizer.operationName(raw.operationKind()));
        if (raw.model() != null) attrs.put(GenAiAttributes.REQUEST_MODEL, raw.model());
        // The plugin expects the message arrays JSON-encoded as string attribute values.
        attrs.put(
                GenAiAttributes.INPUT_MESSAGES,
                writeArray(normalizeMessages(raw.input(), "user", false, mediaStore, projectId)));
        attrs.put(
                GenAiAttributes.OUTPUT_MESSAGES,
                writeArray(normalizeMessages(raw.output(), "assistant", true, mediaStore, projectId)));
        // Adopt standard usage/tool attrs where the upstream carried them (never invent where a standard exists).
        emitUsageAndTool(attrs, raw.metadata());

        span.putArray("events");
        ObjectNode resource = span.putObject("resource");
        resource.putObject("attributes")
                .put(
                        "service.name",
                        serviceName == null || serviceName.isBlank() ? "tessary-evals-export" : serviceName);
        return span;
    }

    /**
     * Build a {@code [{role, parts:[{type:"text", content}]}]} array from a raw
     * payload. If the payload parses as a role-tagged messages array, roles are
     * preserved; otherwise the whole payload becomes a single message with the
     * given default role. When {@code addFinishReason}, each message carries
     * {@code finish_reason:"end_turn"} (the output shape).
     */
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

        // Fallback: a single message carrying the whole payload (or flattened JSON).
        if (root != null) {
            out.add(message(defaultRole, root, addFinishReason, mediaStore, projectId));
        } else {
            out.add(textMessage(defaultRole, payload == null ? "" : payload, addFinishReason));
        }
        return out;
    }

    /**
     * Build one {@code {role, parts:[…]}} message from a content node, preserving media as real
     * inlined bytes where possible and a labeled placeholder otherwise (#986 — the lossless-or-labeled
     * invariant this class has always documented, now backed by real preservation instead of always a
     * label).
     *
     * <p>The consuming evals-plugin's documented Path-A input format ({@code contract/output_format.md})
     * carries message {@code content} as a plain string, so this does not introduce a new typed part —
     * per Epic 8 Track B Fork 2, a real media byte payload is inlined as a base64 {@code data:} URI
     * <em>inside that same plain-string content field</em> (see {@link #mediaPart}), never a new
     * part-shape or a bundled/referenced export. The message is flagged {@code has_media:true}
     * (renamed from {@code has_image} now that documents are covered too; zero known consumers, per
     * {@code grep -rn "has_image"} at the time of this rename) so a plugin reader can find media parts
     * without scanning every part's content for a label.
     */
    private static ObjectNode message(
            String role,
            @Nullable JsonNode contentNode,
            boolean addFinishReason,
            @Nullable MediaStore mediaStore,
            @Nullable String projectId) {
        var blocks = ContentExtractor.blocksFromContent(contentNode);
        if (blocks.isEmpty()) {
            // Object/array with no recognised parts (e.g. a tool payload) — keep the JSON-flatten
            // behaviour so non-message content still rides through as text.
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

    /** Append the accumulated text as one {@code {type:text}} part and reset the buffer. */
    private static void flushText(ArrayNode parts, StringBuilder textRun) {
        if (textRun.length() == 0) return;
        parts.addObject().put("type", "text").put("content", textRun.toString());
        textRun.setLength(0);
    }

    /**
     * A media (image or document) block's export part, inlining real bytes where possible — see
     * {@link #inlineDataUri}. When bytes cannot be recovered (no store handed in, an unresolvable ref, a
     * real http(s) URL that is deliberately never fetched at export time — same no-fetch posture the
     * judge boundary and ingest already hold for URLs), a document block falls back to its
     * already-extracted {@code text} when one is present (see {@link #documentTextFallback}) — mirroring
     * {@code ContentBlocks.documentRefContent}'s identical text-first priority at the judge boundary, so
     * a media-store miss (e.g. retention GC'ing the backing {@code media_object}) doesn't discard PDF
     * text extracted for free at ingest. Only when neither bytes nor text are recoverable does this fall
     * to the honest content-free label this class has always emitted: {@code [image: <url>]} /
     * {@code [image omitted: <mediaType>]}, and their {@code document} counterparts. Never a silent
     * collapse either way.
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
     * A document block's already-extracted {@code text} (populated at ingest by
     * {@code MediaExternalizer}), labeled the same way the judge boundary labels it — or
     * {@link Optional#empty()} when there is no usable text: an image block (images carry no extracted
     * text), a blank/missing {@code text} field, or {@code text} equal to
     * {@link PdfTextExtractor#DOCUMENT_TEXT_UNAVAILABLE_MARKER} (extraction itself failed at ingest, so
     * the marker is not real content and must not be shipped as if it were).
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
     * The real bytes as an inline {@code data:<mime>;base64,<bytes>} URI, or {@code null} when they
     * cannot be recovered without a network fetch this class does not perform:
     * <ul>
     *   <li>{@code image_b64}/{@code document_b64} — the bytes are already inline verbatim; no
     *       MediaStore needed at all.</li>
     *   <li>{@code image_url}/{@code document_url} — inlined only when {@code url} is ITSELF already a
     *       {@code data:} URI (verbatim reuse); a real http(s) URL is never fetched here.</li>
     *   <li>{@code image_ref}/{@code document_ref} — rehydrated via {@code mediaStore.get}; {@code null}
     *       when the store is absent or the id does not resolve.</li>
     * </ul>
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
            case ContentBlock.TYPE_IMAGE_REF, ContentBlock.TYPE_DOCUMENT_REF -> {
                if (mediaStore == null || projectId == null || b.data() == null) yield null;
                Optional<StoredMedia> stored = mediaStore.get(projectId, new MediaRef(b.data()));
                if (stored.isEmpty()) yield null;
                StoredMedia m = stored.get();
                String mime = m.mediaType() == null || m.mediaType().isBlank() ? defaultedMediaType(b) : m.mediaType();
                yield "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(m.bytes());
            }
            default -> null;
        };
    }

    /** {@code b.mediaType()}, defaulted by modality when blank ({@code image/png} / {@code application/pdf}
     *  — the same defaults {@link ContentBlock}'s own factories use). */
    private static String defaultedMediaType(ContentBlock b) {
        // Read the accessor ONCE into a local: calling b.mediaType() again after the null guard is a
        // fresh @Nullable return as far as SpotBugs is concerned, and it fails the build on it
        // (NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE).
        String mediaType = b.mediaType();
        if (mediaType != null && !mediaType.isBlank()) return mediaType;
        return b.isDocument() ? "application/pdf" : "image/png";
    }

    /** The pre-#986 honest placeholder label — still emitted whenever {@link #inlineDataUri} can't
     *  recover real bytes (see its doc for the three cases). A URL-bearing block labels with the URL; a
     *  bytes-bearing block labels with its media type. */
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
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "[]";
        }
    }

    /**
     * Emit {@code gen_ai.usage.*} and {@code gen_ai.tool.*} attributes when the upstream supplied them in
     * {@link RawEntry#metadata()} (e.g. set by {@link ai.tessary.evals.ingest.OpenInferenceNormalizer} from
     * OpenInference {@code llm.token_count.*}). We adopt the standard attribute key where one exists and accept a
     * couple of common provider aliases; absent values are simply not emitted. Token values coerce to long.
     */
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

    /** Put the first present metadata key as a long attribute, coercing numbers/numeric strings. */
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
                    // not a numeric string — try the next alias
                }
            }
        }
    }

    /** Put the first present non-blank metadata key as a string attribute. */
    private static void putString(ObjectNode attrs, String attrKey, Map<String, Object> metadata, String... metaKeys) {
        for (String k : metaKeys) {
            Object v = metadata.get(k);
            if (v instanceof String s && !s.isBlank()) {
                attrs.put(attrKey, s);
                return;
            }
        }
    }

    /**
     * An explicitly-declared provider/system from upstream metadata, if any. Accepts either the export name
     * ({@code gen_ai.system}) or the renamed live-path name ({@code gen_ai.provider.name}); returns {@code null}
     * when neither is present, so the caller falls back to {@link #inferSystem(String)} on the model.
     */
    private static @Nullable String declaredSystem(@Nullable Map<String, Object> metadata) {
        if (metadata == null) return null;
        for (String k : new String[] {GenAiAttributes.SYSTEM, GenAiAttributes.PROVIDER_NAME}) {
            if (metadata.get(k) instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    /** Map a model name to the OTel {@code gen_ai.system} value the plugin keys on. */
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
