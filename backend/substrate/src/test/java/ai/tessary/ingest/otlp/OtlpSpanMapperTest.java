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
 * Unit tests for the OTLP-span → canonical {@link RawEntry} mapping: native {@code gen_ai.*}
 * read, OpenInference reuse, hex id encoding for the parent graph, nano→ISO timestamps, and the
 * OTLP-native structural fields (end-time + messages) the write path reads.
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
        // A raw OpenLLMetry framework span (not pre-canonicalized by a producer): no gen_ai.operation.name,
        // I/O under traceloop.entity.*. The foreign-sender fallback maps it to a canonical kind + I/O.
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
        // When both are present, the native gen_ai.operation.name is authoritative (fallback never overrides).
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
    void genAiMessages_threadedOntoRawEntryMessageFields() {
        String input = "[{\"role\":\"user\",\"content\":\"hi\"}]";
        String output = "[{\"role\":\"assistant\",\"content\":\"hello\"}]";
        Span span = Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {0x01}))
                .addAttributes(kv(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_CHAT))
                .addAttributes(kv(GenAiAttributes.INPUT_MESSAGES, input))
                .addAttributes(kv(GenAiAttributes.OUTPUT_MESSAGES, output))
                .build();

        RawEntry e = mapper.toRawEntries(request(span)).get(0);
        assertEquals(input, e.inputMessagesJson());
        assertEquals(output, e.outputMessagesJson());
        // Messages also seed input/output so the observation row carries content even with no scalar field.
        assertEquals(input, e.input());
        assertEquals(output, e.output());
    }

    @Test
    void indexedFlattenedMessages_reconstructedIntoCanonicalArray_orderedByIndex() {
        // OpenLLMetry/Traceloop encode messages as gen_ai.prompt.N.role|content. They are
        // reconstructed into the same [{role, content}] array — ordered by N even when the attrs are not.
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
        // Precedence: the canonical structured array always wins; the flattened duplicate is ignored.
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
        // OTLP end-time threaded on even for the OI path (for latency).
        assertEquals("1970-01-01T00:00:03Z", e.endTimestamp());
    }

    @Test
    void spanEvents_areNotIngested() {
        // The substrate types no span events. A gen_ai.evaluation.result event (the standard feedback
        // carrier) and a tessary.agent.self_report event are alike ignored: the span still maps, and
        // nothing on the RawEntry carries the event. The attribute bag is the only thing that survives.
        Span span = Span.newBuilder()
                .setSpanId(ByteString.copyFrom(new byte[] {0x08}))
                .addAttributes(kv(GenAiAttributes.OPERATION_NAME, GenAiAttributes.OP_CHAT))
                .addEvents(Span.Event.newBuilder()
                        .setName("gen_ai.evaluation.result")
                        .addAttributes(kv("gen_ai.evaluation.name", "thumbs_down"))
                        .build())
                .addEvents(Span.Event.newBuilder()
                        .setName("tessary.agent.self_report")
                        .addAttributes(kv("tessary.self_report.category", "missing_context"))
                        .build())
                .build();

        List<RawEntry> entries = mapper.toRawEntries(request(span));
        assertEquals(1, entries.size());
        assertEquals(KindNormalizer.LLM, entries.get(0).operationKind());
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
}
