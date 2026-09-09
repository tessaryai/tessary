// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Normalizes an OpenInference ({@code llm.*} / {@code openinference.*}) span into the platform's canonical
 * {@code gen_ai.*} vocabulary at the {@link RawEntry}-construction boundary — the same edge
 * {@code LangfuseSource.operationKindOf} reads native {@code gen_ai.operation.name} at. After this step the
 * whole downstream is provider-agnostic: an OpenInference span and a native {@code gen_ai.*} span produce the
 * <em>same</em> canonical {@link RawEntry} (same {@code operationKind}, {@code model}, message structure), which
 * re-emits via {@link ai.tessary.ingest.export.TraceSpanMapper} to the same {@code gen_ai.*} span.
 *
 * <p>This is a standalone seam: an adapter (Braintrust), the trace-upload parser, and a future OTLP receiver all
 * call the same normalizer. It is <b>dormant</b> unless OpenInference keys are present — {@link #isOpenInference}
 * gates it — so non-OI inputs are untouched.
 *
 * <p>Authority: the key map is {@code docs/reference/trace-schema.md} (§OpenInference → gen_ai), realized via
 * {@link GenAiAttributes}. Never invent an attribute where a standard {@code gen_ai.*} one exists. Per the
 * project's never-truncate invariant, message content is carried whole — bounded by count upstream, never clipped.
 */
public final class OpenInferenceNormalizer {

    // Deliberately a static bare mapper, not the shared JacksonConfig bean: this is a
    // non-Spring static utility, and the @Primary bean has identical strict semantics.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenInferenceNormalizer() {}

    /**
     * The canonical fields an OpenInference span yields. {@code input}/{@code output} are JSON-encoded
     * {@code [{role, content}]} arrays (the role-tagged shape {@link ai.tessary.ingest.export.TraceSpanMapper}
     * already understands); {@code usage} carries {@code gen_ai.usage.*} and any {@code gen_ai.tool.*} for the
     * caller to merge into {@code RawEntry.metadata}.
     */
    public record Canonical(
            @Nullable String operationKind,
            @Nullable String model,
            @Nullable String system,
            @Nullable String input,
            @Nullable String output,
            Map<String, Object> usage) {}

    /** True when the attribute map carries any OpenInference signal we know how to normalize. */
    public static boolean isOpenInference(@Nullable Map<String, ? extends @Nullable Object> attrs) {
        if (attrs == null) return false;
        return attrs.containsKey(GenAiAttributes.OI_SPAN_KIND)
                || attrs.containsKey(GenAiAttributes.OI_MODEL_NAME)
                || attrs.containsKey(GenAiAttributes.OI_INPUT_MESSAGES)
                || attrs.containsKey(GenAiAttributes.OI_OUTPUT_MESSAGES);
    }

    /** True when the JSON node (an attributes object) carries any OpenInference signal. */
    public static boolean isOpenInference(@Nullable JsonNode attrs) {
        if (attrs == null || !attrs.isObject()) return false;
        return attrs.has(GenAiAttributes.OI_SPAN_KIND)
                || attrs.has(GenAiAttributes.OI_MODEL_NAME)
                || attrs.has(GenAiAttributes.OI_INPUT_MESSAGES)
                || attrs.has(GenAiAttributes.OI_OUTPUT_MESSAGES);
    }

    /**
     * Normalize an OpenInference attribute object into canonical {@code gen_ai.*} fields. Tolerant: missing keys
     * yield {@code null} fields; never throws on shape surprises (degrades to what it can read). Returns
     * {@code null} only when {@code attrs} carries no OpenInference signal at all.
     *
     * @param attrs the span's flattened OpenInference attributes as a JSON object (e.g. {@code llm.input_messages}
     *     either as a nested array or a JSON-encoded string)
     */
    public static @Nullable Canonical normalize(@Nullable JsonNode attrs) {
        if (attrs == null || !isOpenInference(attrs)) return null;

        String spanKind = text(attrs.get(GenAiAttributes.OI_SPAN_KIND));
        String opName = GenAiAttributes.operationNameForSpanKind(spanKind);
        String operationKind = KindNormalizer.normalize(opName);

        String model = text(attrs.get(GenAiAttributes.OI_MODEL_NAME));
        // Model-presence backstop, mirroring LangfuseSource: an LLM span with a model but an unmapped/absent
        // span-kind is a single LLM call.
        if (operationKind == null && model != null && !model.isBlank()) operationKind = KindNormalizer.LLM;

        String system =
                firstNonBlank(text(attrs.get(GenAiAttributes.OI_SYSTEM)), text(attrs.get(GenAiAttributes.OI_PROVIDER)));

        String input = roleTaggedMessages(attrs.get(GenAiAttributes.OI_INPUT_MESSAGES), false);
        String output = roleTaggedMessages(attrs.get(GenAiAttributes.OI_OUTPUT_MESSAGES), true);

        Map<String, Object> usage = new LinkedHashMap<>();
        putLong(usage, GenAiAttributes.USAGE_INPUT_TOKENS, attrs.get(GenAiAttributes.OI_TOKEN_COUNT_PROMPT));
        putLong(usage, GenAiAttributes.USAGE_OUTPUT_TOKENS, attrs.get(GenAiAttributes.OI_TOKEN_COUNT_COMPLETION));
        // gen_ai has no standard "total" usage attr; keep the OI total under its own key so nothing is invented.
        JsonNode total = attrs.get(GenAiAttributes.OI_TOKEN_COUNT_TOTAL);
        if (total != null && total.isNumber()) usage.put("gen_ai.usage.total_tokens", total.asLong());

        // Tool-call name/id also land STRUCTURALLY (gen_ai.tool.name / gen_ai.tool.call.id) — not just folded into
        // output-message text — so TraceSpanMapper.emitUsageAndTool re-emits them and the data is preserved as
        // structured attributes per the never-truncate invariant, matching the schema-doc mapping. We carry the
        // first tool call on the span (the gen_ai.tool.* attrs are single-valued); full args stay in message text.
        putFirstToolCall(usage, attrs.get(GenAiAttributes.OI_OUTPUT_MESSAGES));

        return new Canonical(operationKind, model, system, input, output, usage);
    }

    /**
     * Build a canonical {@link RawEntry} from an OpenInference attribute object, threading the transport-level
     * identifiers the attributes don't carry (ids, urls, timestamps, parent/trace). Returns {@code null} when the
     * attributes are not OpenInference, so a caller can fall through to its existing path.
     */
    public static @Nullable RawEntry toRawEntry(
            @Nullable JsonNode attrs,
            @Nullable String sourceExternalId,
            @Nullable String sourceUrl,
            @Nullable String name,
            @Nullable String parentId,
            @Nullable String traceId,
            @Nullable String timestamp,
            @Nullable Map<String, Object> extraMetadata) {
        if (attrs == null) return null;
        Canonical c = normalize(attrs);
        if (c == null) return null;

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (extraMetadata != null) metadata.putAll(extraMetadata);
        metadata.putAll(c.usage());
        // A declared OI provider/system is authoritative — thread it onto the canonical attrs so it survives
        // even when TraceSpanMapper.inferSystem can't recognize the model (otherwise the declared provider would
        // silently collapse to "other"). Carry it under BOTH names: gen_ai.system for the export/plugin reader,
        // gen_ai.provider.name for the live/OTLP path (see GenAiAttributes' system-vs-provider note).
        if (c.system() != null) {
            metadata.put(GenAiAttributes.SYSTEM, c.system());
            metadata.put(GenAiAttributes.PROVIDER_NAME, c.system());
        }
        // session.id is a standard (non-gen_ai) attribute OpenInference uses bare — carry it through verbatim.
        String sessionId = text(attrs.get(GenAiAttributes.SESSION_ID));
        if (sessionId != null) metadata.put(GenAiAttributes.SESSION_ID, sessionId);

        return new RawEntry(
                sourceExternalId,
                sourceUrl,
                name,
                c.input(),
                c.output(),
                c.model(),
                metadata,
                parentId,
                traceId,
                timestamp,
                c.operationKind());
    }

    // ----- message normalization -------------------------------------------------------------------

    /**
     * Convert an OpenInference {@code llm.{input,output}_messages} value into a role-tagged
     * {@code [{role, content}]} JSON string — the shape {@link ai.tessary.ingest.export.TraceSpanMapper}
     * already preserves roles from. Accepts the value either as a JSON array or a JSON-encoded string. Output
     * messages additionally fold any {@code message.tool_calls} into a textual {@code content} so a tool call is
     * not dropped (gen_ai carries tool calls as message parts; we keep them visible as text — never truncated).
     */
    private static @Nullable String roleTaggedMessages(@Nullable JsonNode value, boolean output) {
        JsonNode arr = asArray(value);
        if (arr == null) return null;
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode m : arr) {
            if (!m.isObject()) continue;
            ObjectNode msg = MAPPER.createObjectNode();
            String role = text(m.get(GenAiAttributes.OI_MESSAGE_ROLE));
            msg.put("role", role == null || role.isBlank() ? (output ? "assistant" : "user") : role);
            msg.put("content", messageContent(m, output));
            out.add(msg);
        }
        if (out.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            return null;
        }
    }

    /** Extract a message's content text: scalar {@code message.content}, multimodal {@code message.contents}, plus tool calls. */
    private static String messageContent(JsonNode m, boolean output) {
        StringBuilder sb = new StringBuilder();

        String scalar = text(m.get(GenAiAttributes.OI_MESSAGE_CONTENT));
        if (scalar != null && !scalar.isEmpty()) sb.append(scalar);

        JsonNode contents = asArray(m.get(GenAiAttributes.OI_MESSAGE_CONTENTS));
        if (contents != null) {
            for (JsonNode part : contents) {
                String t = text(part.get(GenAiAttributes.OI_MESSAGE_CONTENT_TEXT));
                if (t != null && !t.isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t);
                }
            }
        }

        if (output) {
            JsonNode toolCalls = asArray(m.get(GenAiAttributes.OI_MESSAGE_TOOL_CALLS));
            if (toolCalls != null) {
                for (JsonNode tc : toolCalls) {
                    String fn = text(tc.get(GenAiAttributes.OI_TOOL_CALL_FUNCTION_NAME));
                    String args = text(tc.get(GenAiAttributes.OI_TOOL_CALL_FUNCTION_ARGS));
                    if (fn == null && args == null) continue;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append("[tool_call ").append(fn == null ? "" : fn);
                    if (args != null) sb.append(' ').append(args);
                    sb.append(']');
                }
            }
        }
        return sb.toString();
    }

    /**
     * Find the first {@code message.tool_calls[]} entry across the output messages and put its function name and
     * call id into {@code target} under the canonical {@code gen_ai.tool.name} / {@code gen_ai.tool.call.id} keys,
     * so {@link ai.tessary.ingest.export.TraceSpanMapper#toSpan} re-emits them as structured attributes (the
     * full argument payload still rides through in the output-message text). Single-valued, mirroring the
     * single-valued {@code gen_ai.tool.*} span attributes.
     */
    private static void putFirstToolCall(Map<String, Object> target, @Nullable JsonNode outputMessages) {
        JsonNode arr = asArray(outputMessages);
        if (arr == null) return;
        for (JsonNode m : arr) {
            if (!m.isObject()) continue;
            JsonNode toolCalls = asArray(m.get(GenAiAttributes.OI_MESSAGE_TOOL_CALLS));
            if (toolCalls == null) continue;
            for (JsonNode tc : toolCalls) {
                if (!tc.isObject()) continue;
                String fn = text(tc.get(GenAiAttributes.OI_TOOL_CALL_FUNCTION_NAME));
                String id = text(tc.get(GenAiAttributes.OI_TOOL_CALL_ID));
                if (fn != null && !fn.isBlank()) target.put(GenAiAttributes.TOOL_NAME, fn);
                if (id != null && !id.isBlank()) target.put(GenAiAttributes.TOOL_CALL_ID, id);
                if (target.containsKey(GenAiAttributes.TOOL_NAME) || target.containsKey(GenAiAttributes.TOOL_CALL_ID))
                    return;
            }
        }
    }

    // ----- helpers ---------------------------------------------------------------------------------

    /**
     * Read a node that is either a JSON array or a JSON-encoded array string; else {@code null}.
     *
     * <p><b>Deferred:</b> the indexed/flattened OTLP attribute form — where a message array is delivered as scalar
     * keys like {@code llm.input_messages.0.message.role} / {@code …0.message.content} rather than a nested array
     * or JSON-encoded string — is intentionally NOT handled here. That re-assembly belongs with the OTLP receiver
     * work (deferred, see {@code docs/reference/trace-schema.md} "Out of scope"); a flattened span normalizes with
     * {@code null} messages until then.
     */
    private static @Nullable JsonNode asArray(@Nullable JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isArray()) return node;
        if (node.isTextual()) {
            try {
                JsonNode parsed = MAPPER.readTree(node.asText());
                return parsed.isArray() ? parsed : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static @Nullable String text(@Nullable JsonNode n) {
        if (n == null || n.isNull()) return null;
        return n.isTextual() ? n.asText() : n.toString();
    }

    private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static void putLong(Map<String, Object> target, String key, @Nullable JsonNode n) {
        if (n != null && n.isNumber()) target.put(key, n.asLong());
    }
}
