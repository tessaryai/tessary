// SPDX-License-Identifier: Apache-2.0
package ai.tessary.evals.ingest.otlp;

import ai.tessary.evals.ingest.GenAiAttributes;
import ai.tessary.evals.ingest.KindNormalizer;
import ai.tessary.evals.ingest.OpenInferenceNormalizer;
import ai.tessary.evals.ingest.RawEntry;
import ai.tessary.evals.ingest.TraceloopNormalizer;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps a decoded OTLP {@link ExportTraceServiceRequest} into the platform's canonical
 * {@link RawEntry} batch — the same normalized shape every pull adapter emits — so the OTLP push
 * front door feeds the <em>identical</em> downstream write path
 * ({@code SubstrateWriter} → {@code SpanBatchWriter}) as Langfuse/Braintrust,
 * and the substrate's idempotency/ordering/de-conflict guarantees apply unchanged.
 *
 * <p><b>Flatten, then normalize at the edge.</b> Each {@code ResourceSpans → ScopeSpans → Span} is
 * flattened to a {@code Map<String,Object>} of its attributes (resource attributes merged in, span
 * attributes winning on a key clash), then:
 * <ul>
 *   <li>If the span carries OpenInference keys ({@code llm.*}/{@code openinference.*}),
 *       {@link OpenInferenceNormalizer#toRawEntry} rewrites them to canonical {@code gen_ai.*} — the
 *       standalone seam its javadoc names "a future OTLP receiver" as a caller. We never fork it.</li>
 *   <li>Otherwise the span is read natively: {@code gen_ai.operation.name} →
 *       {@link KindNormalizer} kind, {@code gen_ai.request.model} → model,
 *       {@code gen_ai.input.messages}/{@code gen_ai.output.messages} threaded onto the OTLP-native
 *       {@link RawEntry} message fields (the structural source the write path uses for
 *       {@code message} rows), and the span end-time onto {@code RawEntry.endTimestamp} (the source
 *       for {@code tool_call.latencyMs}). Neither is available on the pull path.</li>
 * </ul>
 *
 * <p>Span/trace ids are lowercase hex (matching {@code parentSpanId} → {@code parentId} so the
 * enricher's provider span-id graph resolves parent links). Per the never-truncate invariant, no
 * attribute or message content is clipped here — the batch is bounded by span count upstream
 * ({@code OtlpReceiverProperties.maxSpansPerRequest}), never by truncation.
 *
 * <p>A singleton {@link Component} so every OTLP transport (HTTP and gRPC) shares one mapper
 * instance and both front doors normalize identically.
 */
@Component
public final class OtlpSpanMapper {

    private static final Logger log = LoggerFactory.getLogger(OtlpSpanMapper.class);

    private final ObjectMapper mapper;

    public OtlpSpanMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Flatten every span across every resource/scope into one canonical {@link RawEntry} batch. */
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
        // Resource attributes first, span attributes win on a clash (the span is more specific).
        Map<String, Object> attrs = new LinkedHashMap<>(resourceAttrs);
        attrs.putAll(flattenAttributes(span.getAttributesList()));

        // emptyToNull on all three ids: a missing/empty id must degrade to null (which the write path's
        // validation drops), not "" — an empty sourceExternalId would collide every empty-id span in the
        // batch into one row, cross-linking unrelated spans.
        String spanId = emptyToNull(hex(span.getSpanId().toByteArray()));
        String traceId = emptyToNull(hex(span.getTraceId().toByteArray()));
        String parentId = emptyToNull(hex(span.getParentSpanId().toByteArray()));
        String name = emptyToNull(span.getName());
        String start = isoFromUnixNano(span.getStartTimeUnixNano());
        String end = isoFromUnixNano(span.getEndTimeUnixNano());

        // A span error status is structural — surface it into attrs (so it lands in metadata) for BOTH the
        // OpenInference and native paths to read. Set before the OI branch so an OI span's error is captured too.
        if (span.hasStatus() && span.getStatus().getCode() == Status.StatusCode.STATUS_CODE_ERROR) {
            attrs.put("level", "ERROR");
            String message = emptyToNull(span.getStatus().getMessage());
            if (message != null) attrs.put("statusMessage", message);
        }

        // OpenInference inputs route through the standalone normalizer (reused, never forked).
        if (OpenInferenceNormalizer.isOpenInference(attrs)) {
            RawEntry oi = OpenInferenceNormalizer.toRawEntry(
                    mapper.valueToTree(attrs), spanId, null, name, parentId, traceId, start, attrs);
            if (oi != null) {
                // Thread the OTLP-native structural fields the normalizer doesn't know about
                // (end-time for latency; OI messages are already folded into input/output text).
                return withOtlpFields(oi, end, null, null);
            }
        }

        // Native gen_ai.* read.
        String operationName = str(attrs, GenAiAttributes.OPERATION_NAME);
        // Foreign-sender fallback: a raw OpenLLMetry framework span carries traceloop.span.kind
        // instead of gen_ai.operation.name. Producers that emit canonical gen_ai already set this, so this
        // only fires for foreign senders. Never overrides a native gen_ai operation name.
        if (operationName == null)
            operationName = TraceloopNormalizer.operationName(attrs.get(TraceloopNormalizer.SPAN_KIND));
        // Kind from gen_ai.operation.name alone, plus the one standard
        // attribute discriminator: execute_tool + gen_ai.tool.type=extension → mcp.
        String operationKind = KindNormalizer.normalize(operationName, attrs);
        String model = str(attrs, GenAiAttributes.REQUEST_MODEL);
        // Model-presence backstop, mirroring LangfuseSource / OpenInferenceNormalizer.
        if (operationKind == null && model != null && !model.isBlank()) operationKind = KindNormalizer.LLM;

        String inputMessages = str(attrs, GenAiAttributes.INPUT_MESSAGES);
        String outputMessages = str(attrs, GenAiAttributes.OUTPUT_MESSAGES);
        // OpenLLMetry/Traceloop encode messages as indexed-flattened attrs (gen_ai.prompt.N.role|content).
        // Reconstruct them into the canonical [{role, content}] array ONLY when the structured array is
        // absent — the structured form always wins — and only when the zero-index sentinel key is present, so
        // a non-message span never pays a key scan. The structural source itself stays encoding-agnostic.
        if (inputMessages == null && attrs.containsKey(GenAiAttributes.TRACELOOP_PROMPT_PREFIX + "0.role")) {
            inputMessages = reconstructIndexedMessages(attrs, GenAiAttributes.TRACELOOP_PROMPT_PREFIX);
        }
        if (outputMessages == null && attrs.containsKey(GenAiAttributes.TRACELOOP_COMPLETION_PREFIX + "0.role")) {
            outputMessages = reconstructIndexedMessages(attrs, GenAiAttributes.TRACELOOP_COMPLETION_PREFIX);
        }
        // Plain input/output (a producer's canonicalized carrier) win; else fall back to the raw
        // traceloop.entity.* payload for foreign OpenLLMetry framework spans.
        String input = firstNonBlank(inputMessages, str(attrs, "input"), TraceloopNormalizer.input(attrs));
        String output = firstNonBlank(outputMessages, str(attrs, "output"), TraceloopNormalizer.output(attrs));

        return new RawEntry(
                spanId,
                null,
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
                base.sourceUrl(),
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

    // ----- OpenLLMetry/Traceloop indexed-flattened message reconstruction ---------------------------

    /**
     * Reconstruct an OpenLLMetry/Traceloop indexed-flattened message family ({@code <prefix>N.role} /
     * {@code <prefix>N.content}) into the canonical {@code [{role, content}]} JSON array {@code RawEntry}
     * carries and the write path consumes. Keyed into a {@link java.util.NavigableMap} by the
     * parsed index N so the array is emitted in message order regardless of attribute-map order; a non-integer or
     * malformed index segment is skipped (fail-open — the reconstruction degrades, never throws). Content is
     * carried whole (never clipped — never-truncate invariant). Returns {@code null} when no well-formed
     * indexed entry is found, so the caller leaves the message field absent rather than emitting an empty array.
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
        return writeJson(arr);
    }

    private static @Nullable Integer parseIndex(String segment) {
        try {
            int n = Integer.parseInt(segment);
            return n >= 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ----- attribute flattening --------------------------------------------------------------------

    /** Flatten an OTLP {@code repeated KeyValue} attribute list into a string-keyed map of plain values. */
    private Map<String, Object> flattenAttributes(List<KeyValue> kvs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (KeyValue kv : kvs) {
            Object value = anyValue(kv.getValue());
            if (value != null) out.put(kv.getKey(), value);
        }
        return out;
    }

    /** Convert an OTLP {@link AnyValue} to a plain Java value (String/Long/Double/Boolean/JSON-string). */
    private @Nullable Object anyValue(AnyValue v) {
        return switch (v.getValueCase()) {
            case STRING_VALUE -> v.getStringValue();
            case BOOL_VALUE -> v.getBoolValue();
            case INT_VALUE -> v.getIntValue();
            case DOUBLE_VALUE -> v.getDoubleValue();
            case BYTES_VALUE -> hex(v.getBytesValue().toByteArray());
            case ARRAY_VALUE -> arrayValueJson(v.getArrayValue());
            case KVLIST_VALUE -> kvListJson(v.getKvlistValue());
            // OTLP 1.9+ dictionary encoding (opentelemetry-proto 1.11.0-alpha, #1056): a string given
            // as an index into a string table carried elsewhere in the request. This mapper reads
            // plain values only and does not resolve that table, so the value is dropped (null, same
            // as unset) rather than misread as the index number. Exhaustive switch: a future case
            // will fail compilation here again, on purpose.
            case STRING_VALUE_STRINDEX -> null;
            case VALUE_NOT_SET -> null;
        };
    }

    /** Serialize a complex array attribute to a JSON string (kept whole — never clipped). */
    private @Nullable String arrayValueJson(ArrayValue arr) {
        List<Object> list = new ArrayList<>(arr.getValuesCount());
        for (AnyValue v : arr.getValuesList()) {
            list.add(anyValue(v));
        }
        return writeJson(list);
    }

    /** Serialize a nested key-value-list attribute to a JSON object string. */
    private @Nullable String kvListJson(KeyValueList kvl) {
        ObjectNode node = mapper.createObjectNode();
        for (KeyValue kv : kvl.getValuesList()) {
            node.set(kv.getKey(), mapper.valueToTree(anyValue(kv.getValue())));
        }
        return writeJson(node);
    }

    private @Nullable String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Primitive-derived values never fail here; log so a genuinely unserializable complex
            // attribute isn't dropped into a silent black hole.
            log.debug("otlp attribute dropped: not JSON-serializable", e);
            return null;
        }
    }

    // ----- scalar helpers --------------------------------------------------------------------------

    private static @Nullable String str(Map<String, Object> attrs, String key) {
        Object v = attrs.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    /** Lowercase hex of an id/byte array; {@code ""} for an empty array (the OTLP unset form). */
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

    /** OTLP carries Unix-nanos; render ISO-8601 (null for the unset 0 sentinel). */
    private static @Nullable String isoFromUnixNano(long unixNano) {
        if (unixNano == 0L) return null;
        long seconds = unixNano / 1_000_000_000L;
        long nanos = unixNano % 1_000_000_000L;
        return Instant.ofEpochSecond(seconds, nanos).toString();
    }
}
