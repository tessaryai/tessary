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
 * Normalizes OpenInference spans to {@code gen_ai.*} at the RawEntry boundary, so everything downstream is
 * provider-agnostic. Dormant unless {@link #isOpenInference} says so. Key map:
 * {@code devdocs/reference/trace-schema.md}. Content is never clipped.
 */
public final class OpenInferenceNormalizer {

    // A static bare mapper, not the JacksonConfig bean: this is a non-Spring utility, and the @Primary bean has
    // identical strict semantics.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenInferenceNormalizer() {}

    /**
     * {@code usage} carries {@code gen_ai.usage.*} and any {@code gen_ai.tool.*} for the caller to merge into metadata.
     */
    public record Canonical(
            @Nullable String operationKind,
            @Nullable String model,
            @Nullable String system,
            @Nullable String input,
            @Nullable String output,
            Map<String, Object> usage) {}

    public static boolean isOpenInference(@Nullable Map<String, ? extends @Nullable Object> attrs) {
        if (attrs == null) return false;
        return attrs.containsKey(GenAiAttributes.OI_SPAN_KIND)
                || attrs.containsKey(GenAiAttributes.OI_MODEL_NAME)
                || attrs.containsKey(GenAiAttributes.OI_INPUT_MESSAGES)
                || attrs.containsKey(GenAiAttributes.OI_OUTPUT_MESSAGES);
    }

    /** Never throws on shape surprises; missing keys yield null fields. */
    public static Canonical normalize(JsonNode attrs) {
        String spanKind = text(attrs.get(GenAiAttributes.OI_SPAN_KIND));
        String opName = GenAiAttributes.operationNameForSpanKind(spanKind);
        String operationKind = KindNormalizer.normalize(opName);

        String model = text(attrs.get(GenAiAttributes.OI_MODEL_NAME));
        // An LLM span with a model but no mapped span kind is a single LLM call.
        if (operationKind == null && model != null && !model.isBlank()) operationKind = KindNormalizer.LLM;

        String system =
                firstNonBlank(text(attrs.get(GenAiAttributes.OI_SYSTEM)), text(attrs.get(GenAiAttributes.OI_PROVIDER)));

        String input = roleTaggedMessages(attrs.get(GenAiAttributes.OI_INPUT_MESSAGES), false);
        String output = roleTaggedMessages(attrs.get(GenAiAttributes.OI_OUTPUT_MESSAGES), true);

        Map<String, Object> usage = new LinkedHashMap<>();
        putLong(usage, GenAiAttributes.USAGE_INPUT_TOKENS, attrs.get(GenAiAttributes.OI_TOKEN_COUNT_PROMPT));
        putLong(usage, GenAiAttributes.USAGE_OUTPUT_TOKENS, attrs.get(GenAiAttributes.OI_TOKEN_COUNT_COMPLETION));
        // gen_ai has no standard total; keep OI's under its own key so nothing is invented.
        JsonNode total = attrs.get(GenAiAttributes.OI_TOKEN_COUNT_TOTAL);
        if (total != null && total.isNumber()) usage.put("gen_ai.usage.total_tokens", total.asLong());

        // Also carried structurally so TraceSpanMapper re-emits them; gen_ai.tool.* is single-valued, so only the first
        // call.
        putFirstToolCall(usage, attrs.get(GenAiAttributes.OI_OUTPUT_MESSAGES));

        return new Canonical(operationKind, model, system, input, output, usage);
    }

    public static RawEntry toRawEntry(
            JsonNode attrs,
            @Nullable String sourceExternalId,
            @Nullable String name,
            @Nullable String parentId,
            @Nullable String traceId,
            @Nullable String timestamp,
            @Nullable Map<String, Object> extraMetadata) {
        Canonical c = normalize(attrs);

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (extraMetadata != null) metadata.putAll(extraMetadata);
        metadata.putAll(c.usage());
        // A declared provider is authoritative, so it survives a model inferSystem can't recognize. Carried as both
        // gen_ai.system (export) and gen_ai.provider.name (live path).
        if (c.system() != null) {
            metadata.put(GenAiAttributes.SYSTEM, c.system());
            metadata.put(GenAiAttributes.PROVIDER_NAME, c.system());
        }
        String sessionId = text(attrs.get(GenAiAttributes.SESSION_ID));
        if (sessionId != null) metadata.put(GenAiAttributes.SESSION_ID, sessionId);

        return new RawEntry(
                sourceExternalId,
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

    /** Output messages fold {@code message.tool_calls} into text so a tool call is not dropped. */
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
        return out.toString();
    }

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

    /** The indexed/flattened OTLP form is deliberately not handled here; such a span normalizes with null messages. */
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
