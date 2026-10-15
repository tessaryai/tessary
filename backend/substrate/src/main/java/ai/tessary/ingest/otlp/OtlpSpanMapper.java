// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.otlp;

import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.OpenInferenceNormalizer;
import ai.tessary.ingest.RawEntry;
import ai.tessary.ingest.TraceloopNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.common.v1.KeyValueList;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Maps OTLP into the same {@link RawEntry} shape every pull adapter emits, so the push door feeds the identical write
 * path. Resource attributes are merged under span attributes; OpenInference spans go through
 * {@link OpenInferenceNormalizer} (never forked). Never truncates; the batch is bounded by span count upstream.
 */
@Component
public final class OtlpSpanMapper {

    private final ObjectMapper mapper;

    public OtlpSpanMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<RawEntry> toRawEntries(ExportTraceServiceRequest request) {
        List<RawEntry> out = new ArrayList<>();
        for (ResourceSpans rs : request.getResourceSpansList()) {
            Map<String, Object> resourceAttrs =
                    flattenAttributes(rs.hasResource() ? rs.getResource().getAttributesList() : List.of());
            for (ScopeSpans ss : rs.getScopeSpansList()) {
                for (Span span : ss.getSpansList()) {
                    out.add(toRawEntry(span, resourceAttrs));
                }
            }
        }
        return out;
    }

    private RawEntry toRawEntry(Span span, Map<String, Object> resourceAttrs) {
        // Span attributes win on a clash: the span is more specific.
        Map<String, Object> attrs = new LinkedHashMap<>(resourceAttrs);
        attrs.putAll(flattenAttributes(span.getAttributesList()));

        // A missing id must degrade to null, not "": an empty sourceExternalId would collide every empty-id span in the
        // batch into one row.
        String spanId = emptyToNull(hex(span.getSpanId().toByteArray()));
        String traceId = emptyToNull(hex(span.getTraceId().toByteArray()));
        String parentId = emptyToNull(hex(span.getParentSpanId().toByteArray()));
        String name = emptyToNull(span.getName());
        String start = isoFromUnixNano(span.getStartTimeUnixNano());
        String end = isoFromUnixNano(span.getEndTimeUnixNano());

        // Set before the OI branch so an OI span's error is captured too.
        if (span.hasStatus() && span.getStatus().getCode() == Status.StatusCode.STATUS_CODE_ERROR) {
            attrs.put("level", "ERROR");
            String message = emptyToNull(span.getStatus().getMessage());
            if (message != null) attrs.put("statusMessage", message);
        }

        if (OpenInferenceNormalizer.isOpenInference(attrs)) {
            RawEntry oi = OpenInferenceNormalizer.toRawEntry(
                    mapper.valueToTree(attrs), spanId, name, parentId, traceId, start, attrs);
            // Thread the OTLP-native fields the normalizer doesn't know (end time for latency).
            return withOtlpFields(oi, end, null, null);
        }

        String operationName = str(attrs, GenAiAttributes.OPERATION_NAME);
        // Foreign-sender fallback for raw OpenLLMetry spans; never overrides a native gen_ai operation name.
        if (operationName == null)
            operationName = TraceloopNormalizer.operationName(attrs.get(TraceloopNormalizer.SPAN_KIND));
        // execute_tool + gen_ai.tool.type=extension → mcp.
        String operationKind = KindNormalizer.normalize(operationName, attrs);
        String model = str(attrs, GenAiAttributes.REQUEST_MODEL);
        if (operationKind == null && model != null && !model.isBlank()) operationKind = KindNormalizer.LLM;

        String inputMessages = str(attrs, GenAiAttributes.INPUT_MESSAGES);
        String outputMessages = str(attrs, GenAiAttributes.OUTPUT_MESSAGES);
        // Reconstructed only when the structured array is absent and the zero-index sentinel key exists, so a
        // non-message span never pays a key scan.
        if (inputMessages == null && attrs.containsKey(GenAiAttributes.TRACELOOP_PROMPT_PREFIX + "0.role")) {
            inputMessages = reconstructIndexedMessages(attrs, GenAiAttributes.TRACELOOP_PROMPT_PREFIX);
        }
        if (outputMessages == null && attrs.containsKey(GenAiAttributes.TRACELOOP_COMPLETION_PREFIX + "0.role")) {
            outputMessages = reconstructIndexedMessages(attrs, GenAiAttributes.TRACELOOP_COMPLETION_PREFIX);
        }
        // Plain input/output win; else the raw traceloop.entity.* payload.
        String input = firstNonBlank(inputMessages, str(attrs, "input"), TraceloopNormalizer.input(attrs));
        String output = firstNonBlank(outputMessages, str(attrs, "output"), TraceloopNormalizer.output(attrs));

        return new RawEntry(
                spanId,
                name,
                input,
                output,
                model,
                attrs,
                parentId,
                traceId,
                start,
                operationKind,
                end,
                inputMessages,
                outputMessages);
    }

    private static RawEntry withOtlpFields(
            RawEntry base,
            @Nullable String endTimestamp,
            @Nullable String inputMessagesJson,
            @Nullable String outputMessagesJson) {
        return new RawEntry(
                base.sourceExternalId(),
                base.name(),
                base.input(),
                base.output(),
                base.model(),
                base.metadata(),
                base.parentId(),
                base.traceId(),
                base.timestamp(),
                base.operationKind(),
                endTimestamp,
                inputMessagesJson,
                outputMessagesJson);
    }

    private static @Nullable String firstNonBlank(@Nullable String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /**
     * Keyed by the parsed index so messages come out in order regardless of map order. Malformed indices are skipped;
     * null when nothing well-formed is found.
     */
    private @Nullable String reconstructIndexedMessages(Map<String, Object> attrs, String prefix) {
        java.util.NavigableMap<Integer, ObjectNode> byIndex = new java.util.TreeMap<>();
        int prefixLen = prefix.length();
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith(prefix)) continue;
            int dot = key.indexOf('.', prefixLen);
            if (dot < 0) continue;
            Integer index = parseIndex(key.substring(prefixLen, dot));
            if (index == null) continue;
            String subKey = key.substring(dot + 1);
            Object value = e.getValue();
            if (!(value instanceof String s)) continue;
            ObjectNode msg = byIndex.computeIfAbsent(index, i -> mapper.createObjectNode());
            if (GenAiAttributes.TRACELOOP_MESSAGE_ROLE.equals(subKey)) {
                msg.put(GenAiAttributes.TRACELOOP_MESSAGE_ROLE, s);
            } else if (GenAiAttributes.TRACELOOP_MESSAGE_CONTENT.equals(subKey)) {
                msg.put(GenAiAttributes.TRACELOOP_MESSAGE_CONTENT, s);
            }
        }
        if (byIndex.isEmpty()) return null;
        var arr = mapper.createArrayNode();
        for (ObjectNode msg : byIndex.values()) {
            arr.add(msg);
        }
        return arr.toString();
    }

    private static @Nullable Integer parseIndex(String segment) {
        try {
            int n = Integer.parseInt(segment);
            return n >= 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> flattenAttributes(List<KeyValue> kvs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (KeyValue kv : kvs) {
            Object value = anyValue(kv.getValue());
            if (value != null) out.put(kv.getKey(), value);
        }
        return out;
    }

    private @Nullable Object anyValue(AnyValue v) {
        return switch (v.getValueCase()) {
            case STRING_VALUE -> v.getStringValue();
            case BOOL_VALUE -> v.getBoolValue();
            case INT_VALUE -> v.getIntValue();
            case DOUBLE_VALUE -> v.getDoubleValue();
            case BYTES_VALUE -> hex(v.getBytesValue().toByteArray());
            case ARRAY_VALUE -> arrayValueJson(v.getArrayValue());
            case KVLIST_VALUE -> kvListJson(v.getKvlistValue());
            // OTLP 1.9+ dictionary encoding: the string table is not resolved here, so the value is dropped rather than
            // misread as the index number. The switch is exhaustive so a future case fails compilation.
            case STRING_VALUE_STRINDEX -> null;
            case VALUE_NOT_SET -> null;
        };
    }

    private String arrayValueJson(ArrayValue arr) {
        List<Object> list = new ArrayList<>(arr.getValuesCount());
        for (AnyValue v : arr.getValuesList()) {
            list.add(anyValue(v));
        }
        return mapper.valueToTree(list).toString();
    }

    private String kvListJson(KeyValueList kvl) {
        ObjectNode node = mapper.createObjectNode();
        for (KeyValue kv : kvl.getValuesList()) {
            node.set(kv.getKey(), mapper.valueToTree(anyValue(kv.getValue())));
        }
        return node.toString();
    }

    private static @Nullable String str(Map<String, Object> attrs, String key) {
        Object v = attrs.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    /** {@code ""} for an empty array, the OTLP unset form. */
    private static String hex(byte[] bytes) {
        if (bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static @Nullable String emptyToNull(@Nullable String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Null for the unset 0 sentinel. */
    private static @Nullable String isoFromUnixNano(long unixNano) {
        if (unixNano == 0L) return null;
        long seconds = unixNano / 1_000_000_000L;
        long nanos = unixNano % 1_000_000_000L;
        return Instant.ofEpochSecond(seconds, nanos).toString();
    }
}
