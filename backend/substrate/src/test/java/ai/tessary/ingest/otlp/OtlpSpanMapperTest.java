// SPDX-License-Identifier: Apache-2.0
package ai.tessary.ingest.otlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.tessary.ingest.GenAiAttributes;
import ai.tessary.ingest.KindNormalizer;
import ai.tessary.ingest.RawEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * OTLP span to {@link RawEntry}: native {@code gen_ai.*}, OpenInference reuse, hex ids for the parent graph, nano to
 * ISO timestamps, and the end-time and messages the write path reads.
 */
class OtlpSpanMapperTest {

    private final OtlpSpanMapper mapper = new OtlpSpanMapper(new ObjectMapper());

    private static KeyValue kv(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value).build())
                .build();
    }

    private static ExportTraceServiceRequest request(Span... spans) {
        ScopeSpans.Builder scope = ScopeSpans.newBuilder();
        for (Span s : spans) scope.addSpans(s);
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(
                        ResourceSpans.newBuilder().addScopeSpans(scope).build())
                .build();
    }

    @Test
    void nativeGenAiSpan_mapsToCanonicalRawEntry_withHexIdsAndTimestamps() {
        Span span = Span.newBuilder()
                .setName("chat gpt")
                .setTraceId(ByteString.copyFrom(new byte[] {0x01, 0x23}))
                .setSpanId(ByteString.copyFrom(new byte[] {(byte) 0xAB, (byte) 0xCD}))
                .setParentSpanId(ByteString.copyFrom(new byte[] {0x0F}))
                .setStartTimeUnixNano(1_000_000_000L) // 1970-01-01T00:00:01Z
                .setEndTimeUnixNano(2_500_000_000L) // +1.5s
                .addAttributes(kv(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_CHAT))
                .addAttributes(kv(GenAiAttributes.REQUEST_MODEL, "gpt-x"))
                .build();

        List<RawEntry> out = mapper.toRawEntries(request(span));
        assertEquals(1, out.size());
        RawEntry e = out.get(0);
        assertEquals("abcd", e.sourceExternalId());
        assertEquals("0123", e.traceId());
        assertEquals("0f", e.parentId());
        assertEquals("chat gpt", e.name());
        assertEquals(KindNormalizer.LLM, e.operationKind());
        assertEquals("gpt-x", e.model());
        assertEquals("1970-01-01T00:00:01Z", e.timestamp());
        assertEquals("1970-01-01T00:00:02.500Z", e.endTimestamp());
    }

    @Test
    void foreignTraceloopFrameworkSpan_normalizesToCanonicalKindAndIo() {
        // A raw OpenLLMetry span: no gen_ai.operation.name, I/O under traceloop.entity.*, mapped by the foreign-
        // sender fallback.
        Span span = Span.newBuilder()
                .setName("RetrieverQueryEngine.workflow")
                .setSpanId(ByteString.copyFrom(new byte[] {0x07}))
                .addAttributes(kv("traceloop.span.kind", "workflow"))
                .addAttributes(kv("traceloop.entity.input", "{\"query\":\"what is X?\"}"))
                .addAttributes(kv("traceloop.entity.output", "{\"answer\":\"X is Y\"}"))
                .build();

        RawEntry e = mapper.toRawEntries(request(span)).get(0);
        assertEquals(KindNormalizer.WORKFLOW, e.operationKind(), "traceloop.span.kind=workflow -> workflow");
        assertEquals("{\"query\":\"what is X?\"}", e.input(), "traceloop.entity.input -> input");
        assertEquals("{\"answer\":\"X is Y\"}", e.output(), "traceloop.entity.output -> output");
    }

    @Test
    void nativeGenAiWins_overTraceloopFallback() {
        // The native gen_ai.operation.name wins over the fallback.
        Span span = Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {0x08}))
                .addAttributes(kv(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_CHAT))
                .addAttributes(kv("traceloop.span.kind", "workflow"))
                .build();
        assertEquals(
                KindNormalizer.LLM, mapper.toRawEntries(request(span)).get(0).operationKind());
    }

    @Test
    void emptyParentSpanId_isNull() {
        Span span = Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {0x01}))
                .addAttributes(kv(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_CHAT))
                .build();
        assertNull(mapper.toRawEntries(request(span)).get(0).parentId());
    }

    @Test
    void indexedFlattenedMessages_reconstructedIntoCanonicalArray_orderedByIndex() {
        // gen_ai.prompt.N.role|content is rebuilt into one [{role, content}] array, ordered by N.
        Span span = Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {0x01}))
                .addAttributes(kv(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_CHAT))
                .addAttributes(kv("gen_ai.prompt.1.role", "user"))
                .addAttributes(kv("gen_ai.prompt.1.content", "hi"))
                .addAttributes(kv("gen_ai.prompt.0.role", "system"))
                .addAttributes(kv("gen_ai.prompt.0.content", "be brief"))
                .addAttributes(kv("gen_ai.completion.0.role", "assistant"))
                .addAttributes(kv("gen_ai.completion.0.content", "hello"))
                .build();

        RawEntry e = mapper.toRawEntries(request(span)).get(0);
        assertEquals(
                "[{\"role\":\"system\",\"content\":\"be brief\"},{\"role\":\"user\",\"content\":\"hi\"}]",
                e.inputMessagesJson());
        assertEquals("[{\"role\":\"assistant\",\"content\":\"hello\"}]", e.outputMessagesJson());
        assertEquals(e.inputMessagesJson(), e.input());
        assertEquals(e.outputMessagesJson(), e.output());
    }

    @Test
    void structuredMessagesWin_whenBothStructuredAndFlattenedPresent() {
        // The canonical structured array wins over the flattened duplicate.
        String structured = "[{\"role\":\"user\",\"content\":\"canonical\"}]";
        Span span = Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {0x01}))
                .addAttributes(kv(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_CHAT))
                .addAttributes(kv(GenAiAttributes.INPUT_MESSAGES, structured))
                .addAttributes(kv("gen_ai.prompt.0.role", "user"))
                .addAttributes(kv("gen_ai.prompt.0.content", "flattened-duplicate"))
                .build();

        RawEntry e = mapper.toRawEntries(request(span)).get(0);
        assertEquals(structured, e.inputMessagesJson());
        assertNull(e.outputMessagesJson());
    }

    @Test
    void modelPresenceBackstop_marksUnnamedSpanAsLlm() {
        Span span = Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {0x01}))
                .addAttributes(kv(GenAiAttributes.REQUEST_MODEL, "claude-x"))
                .build();
        assertEquals(
                KindNormalizer.LLM, mapper.toRawEntries(request(span)).get(0).operationKind());
    }

    @Test
    void errorStatus_surfacesAsStructuralLevelForToolErrorRead() {
        Span span = Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {0x01}))
                .addAttributes(kv(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_EXECUTE_TOOL))
                .setStatus(Status.newBuilder()
                        .setCode(Status.StatusCode.STATUS_CODE_ERROR)
                        .setMessage("tool blew up")
                        .build())
                .build();
        RawEntry e = mapper.toRawEntries(request(span)).get(0);
        Map<String, Object> meta = e.metadata();
        assertNotNull(meta);
        assertEquals("ERROR", meta.get("level"));
        assertEquals("tool blew up", meta.get("statusMessage"));
    }

    @Test
    void openInferenceSpan_routesThroughNormalizer() {
        Span span = Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {0x01}))
                .addAttributes(kv(GenAiAttributes.OI_SPAN_KIND, "LLM"))
                .addAttributes(kv(GenAiAttributes.OI_MODEL_NAME, "gpt-oi"))
                .setEndTimeUnixNano(3_000_000_000L)
                .setStartTimeUnixNano(1_000_000_000L)
                .build();
        RawEntry e = mapper.toRawEntries(request(span)).get(0);
        assertEquals(KindNormalizer.LLM, e.operationKind());
        assertEquals("gpt-oi", e.model());
        // End-time threaded on the OI path too, for latency.
        assertEquals("1970-01-01T00:00:03Z", e.endTimestamp());
    }

    @Test
    void flattensAcrossMultipleResourceAndScopeSpans() {
        Span a = Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {0x0A}))
                .addAttributes(kv(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_CHAT))
                .build();
        Span b = Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {0x0B}))
                .addAttributes(kv(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_EXECUTE_TOOL))
                .build();
        ExportTraceServiceRequest req = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .addScopeSpans(ScopeSpans.newBuilder().addSpans(a))
                        .build())
                .addResourceSpans(ResourceSpans.newBuilder()
                        .addScopeSpans(ScopeSpans.newBuilder().addSpans(b))
                        .build())
                .build();
        List<RawEntry> out = mapper.toRawEntries(req);
        assertEquals(2, out.size());
        assertEquals("0a", out.get(0).sourceExternalId());
        assertEquals("0b", out.get(1).sourceExternalId());
        assertTrue(out.get(0).metadata() != null && out.get(0).metadata().containsKey(GenAiAttributes.OPERATION_NAME));
    }

    /**
     * Every OTLP value type survives as its plain value, nested ones as JSON; an unreadable value (unset, or a
     * string-table index) is left out, not stored as a number.
     */
    @Test
    void everyAttributeValueTypeFlattensToItsPlainValue() {
        AnyValue nested = AnyValue.newBuilder()
                .setKvlistValue(io.opentelemetry.proto.common.v1.KeyValueList.newBuilder()
                        .addValues(KeyValue.newBuilder()
                                .setKey("k")
                                .setValue(AnyValue.newBuilder().setBoolValue(false))))
                .build();
        Span span = Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {0x01}))
                .addAttributes(value("b", AnyValue.newBuilder().setBoolValue(true)))
                .addAttributes(value("i", AnyValue.newBuilder().setIntValue(42)))
                .addAttributes(value("d", AnyValue.newBuilder().setDoubleValue(1.5)))
                .addAttributes(value("bytes", AnyValue.newBuilder().setBytesValue(ByteString.copyFrom(new byte[] {
                    0x0a, (byte) 0xff
                }))))
                .addAttributes(value(
                        "arr",
                        AnyValue.newBuilder()
                                .setArrayValue(io.opentelemetry.proto.common.v1.ArrayValue.newBuilder()
                                        .addValues(AnyValue.newBuilder().setStringValue("x"))
                                        .addValues(AnyValue.newBuilder().setIntValue(1))
                                        .addValues(nested))))
                .addAttributes(value("kv", nested.toBuilder()))
                .addAttributes(value("unset", AnyValue.newBuilder()))
                .addAttributes(value("strindex", AnyValue.newBuilder().setStringValueStrindex(3)))
                .build();

        RawEntry e = mapper.toRawEntries(request(span)).get(0);

        assertEquals(
                java.util.Map.of(
                        "b",
                        true,
                        "i",
                        42L,
                        "d",
                        1.5,
                        "bytes",
                        "0aff",
                        "arr",
                        "[\"x\",1,\"{\\\"k\\\":false}\"]",
                        "kv",
                        "{\"k\":false}"),
                e.metadata());
    }

    /** A non-integer index segment is skipped, not fatal. */
    @Test
    void indexedFlattenedMessages_skipMalformedIndexes() {
        Span span = Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {0x01}))
                .addAttributes(kv(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_CHAT))
                .addAttributes(kv("gen_ai.prompt.0.role", "user"))
                .addAttributes(kv("gen_ai.prompt.0.content", "hi"))
                .addAttributes(kv("gen_ai.prompt.x.role", "system"))
                .addAttributes(kv("gen_ai.prompt.-1.role", "system"))
                .build();

        assertEquals(
                "[{\"role\":\"user\",\"content\":\"hi\"}]",
                mapper.toRawEntries(request(span)).get(0).inputMessagesJson());
    }

    private static KeyValue value(String key, AnyValue.Builder value) {
        return KeyValue.newBuilder().setKey(key).setValue(value).build();
    }
}
